# ADR-0024: Gateway 내부 서비스 호출 방식 — WebClient

## Status
Accepted

---

## Context

ADR-0023에서 API Gateway에 홈 화면 Aggregation 엔드포인트를 추가하기로 결정했다.
이 엔드포인트는 내부 서비스(routine-service, challenge-service, notification-service)에
데이터를 요청해야 하며, 그 통신 방식을 결정해야 했다.

검토한 옵션: **WebClient**, **OpenFeign**, **gRPC**

---

## Options Considered

### OpenFeign (기각)

OpenFeign은 Blocking 동기 방식으로 동작한다.
Spring Cloud Gateway는 **WebFlux(Reactive)** 기반이므로, OpenFeign을 사용하면
논블로킹 스레드를 블로킹하여 게이트웨이 성능이 저하된다.

> Gateway 환경에서 OpenFeign 사용 금지.
> 일반 Spring MVC 서비스에서는 사용 가능하나,
> 현재 프로젝트에서 서비스 간 동기 호출은 gRPC로 대체하므로 실질적으로 사용처가 없다.

---

### gRPC (Aggregation 목적 불채택)

gRPC는 강타입 계약과 낮은 레이턴시가 필요한 **서비스 간 동기 호출**에 사용한다.
Aggregation 엔드포인트의 목적은 단순 데이터 조합이므로 gRPC 도입 이점이 크지 않다.
또한 Gateway는 gRPC 서버가 아닌 HTTP 서버로 운영한다.

| 케이스 | 통신 방식 |
|--------|-----------|
| routine-service → challenge-service (챌린지 검증) | gRPC |
| chat-service → challenge-service (멤버 검증) | gRPC |
| Gateway → 내부 서비스 (Aggregation) | WebClient (HTTP) |

---

### WebClient (채택)

Spring WebFlux 네이티브 비동기 HTTP 클라이언트.
Gateway의 Reactive 환경과 완전히 호환되며, `Mono.zip()`과 결합하여
**병렬 호출 → 응답 취합**을 논블로킹으로 처리할 수 있다.

---

## Decision

**API Gateway에서 내부 서비스 호출 시 WebClient를 사용한다.**

서비스 간 동기 호출은 gRPC, 비동기 이벤트는 Kafka(Outbox 패턴)로 기존 전략을 유지한다.

### 전체 통신 방식 요약

```
클라이언트  →  Gateway                        HTTP (REST)
Gateway     →  내부 서비스 (Aggregation)      HTTP + WebClient (Mono.zip 병렬 호출)
서비스      →  서비스 (동기)                  gRPC
서비스      →  서비스 (비동기 이벤트)          Kafka (Outbox 패턴)
서비스 내부    비동기 작업                     PGMQ
```

### 구현 예시

```java
// Gateway Aggregation 엔드포인트
@GetMapping("/api/v1/home")
public Mono<HomeResponse> getHome(@RequestHeader("X-User-Id") Long userId) {
    Mono<RoutineSummary>         routines    = routineClient.getTodaySummary(userId);
    Mono<List<ChallengeSummary>> challenges  = challengeClient.getMyChallenges(userId);
    Mono<Integer>                unreadCount = notificationClient.getUnreadCount(userId);

    return Mono.zip(routines, challenges, unreadCount)
               .map(tuple -> HomeResponse.of(
                   tuple.getT1(),
                   tuple.getT2(),
                   tuple.getT3()
               ));
}
```

`Mono.zip()`으로 병렬 호출하므로 응답 시간은 가장 느린 서비스 1개의 시간으로 수렴한다.

---

## Consequences

### Positive

- WebFlux 환경과 완전 호환 — 논블로킹 유지
- `Mono.zip()` 병렬 호출로 응답 시간 최적화
- OpenFeign 혼용으로 인한 스레드 블로킹 위험 제거

### Negative

- Gateway에서 WebClient Bean 관리 및 각 서비스별 Client 클래스 구현 필요
- 내부 서비스 중 하나가 지연/장애 시 홈 화면 전체 응답 지연 가능
  → 각 WebClient 호출에 `timeout` + `onErrorResume` fallback 처리 필수

---

## 관련 ADR

- **ADR-0007**: 서비스 간 통신 전략 전체 요약
- **ADR-0023**: 홈 화면 Aggregation 엔드포인트 도입 결정
