# ADR-0023: 홈 화면 데이터 취합 전략 — Gateway Aggregation 엔드포인트

## Status
Accepted

---

## Context

홈 화면은 루틴, 챌린지, 알림 등 여러 도메인의 데이터를 동시에 보여줘야 한다.
Routinely는 MSA 구조로 각 도메인이 독립된 서비스로 분리되어 있어,
클라이언트가 홈 화면을 렌더링하기 위한 방식 세 가지를 검토했다.

---

## Options Considered

### 방식 1 — 클라이언트에서 각 서비스에 개별 요청 (기각)

클라이언트(React)가 routine-service, challenge-service, notification-service에 각각 직접 요청한다.

| 문제 | 설명 |
|------|------|
| 네트워크 왕복 N회 | 요청 수만큼 홈 화면 로딩 지연 |
| 클라이언트-서비스 결합 | 클라이언트가 MSA 내부 구조를 직접 알아야 함 |
| 변경 취약성 | 내부 서비스 구조 변경 시 클라이언트 코드도 함께 수정 필요 |

---

### 방식 2 — 별도 BFF 서비스 도입 (기각)

클라이언트 전용 취합 서비스를 독립 컨테이너로 운영한다.

**기각 이유:**

- 현재 앱 서비스 7개 + Observability 스택 5개로 이미 **12개 컨테이너** 운영 중
- MVP 단계에서 서비스를 추가로 도입하면 관리 포인트와 운영 복잡도가 증가
- BFF는 Mobile / Web / Partner 등 **클라이언트 종류가 여럿일 때** 진가를 발휘하나,
  Routinely MVP는 **웹 단일 클라이언트**이므로 별도 서비스를 분리할 이유가 없음

---

### 방식 3 — Gateway에 Aggregation 엔드포인트 추가 (채택)

기존 Gateway 모듈 안에 홈 화면 전용 엔드포인트를 추가한다.

- 새 컨테이너 없이 기존 Gateway에 코드만 추가 → 관리 부담 없음
- Gateway 내부에서 `WebFlux Mono.zip()`으로 각 서비스에 **병렬 호출**
- 클라이언트는 단일 엔드포인트만 호출
- MSA 내부 구조가 클라이언트에 노출되지 않음

---

## Decision

**API Gateway에 Aggregation 엔드포인트를 추가하는 방식(방식 3)을 채택한다.**

BFF 개념 자체는 적용하되, 별도 서비스로 분리하지 않고 **기존 Gateway 모듈 안에 구현**한다.

---

## Implementation

### 호출 흐름

```
클라이언트
  └── GET /api/v1/home
        └── API Gateway (Aggregation)
              ├── routine-service      → 오늘 루틴 목록 + 달성률
              ├── challenge-service    → 참여 중인 챌린지 요약
              └── notification-service → 안읽은 알림 수
              └── 취합 후 단일 응답 반환
```

내부 호출은 `Mono.zip()`으로 **병렬 실행**되므로, 응답 시간은 가장 느린 서비스 1개의 시간으로 수렴한다.

### 응답 구조

```json
{
  "routines": {
    "todayTotal": 5,
    "completedCount": 3,
    "achievementRate": 67,
    "items": [
      { "id": 1, "title": "아침 스트레칭", "status": "COMPLETED" },
      { "id": 2, "title": "독서 30분",     "status": "PENDING"   }
    ]
  },
  "challenges": {
    "items": [
      { "id": 1, "title": "30일 운동 챌린지", "dDay": 18, "achievementRate": 67 }
    ]
  },
  "notifications": {
    "unreadCount": 2
  }
}
```

### 구현 예시 (Spring Cloud Gateway)

```java
@GetMapping("/api/v1/home")
public Mono<HomeResponse> getHome(@RequestHeader("X-User-Id") Long userId) {
    Mono<RoutineSummary>        routines    = routineClient.getTodaySummary(userId);
    Mono<List<ChallengeSummary>> challenges = challengeClient.getMyChallenges(userId);
    Mono<Integer>               unreadCount = notificationClient.getUnreadCount(userId);

    return Mono.zip(routines, challenges, unreadCount)
               .map(tuple -> HomeResponse.of(
                   tuple.getT1(),
                   tuple.getT2(),
                   tuple.getT3()
               ));
}
```

---

## Consequences

### Positive

- 클라이언트 요청 수 감소 (N번 → 1번)
- 병렬 호출로 응답 시간 최적화
- MSA 내부 구조가 클라이언트에 노출되지 않음
- 신규 컨테이너 없이 기존 Gateway 모듈에 코드만 추가

### Negative

- Gateway가 단순 라우팅 외에 Aggregation 로직을 일부 담당하게 됨
- 내부 서비스 중 하나가 장애 시 홈 화면 전체에 영향을 줄 수 있음
  → 각 서비스 호출에 **fallback 처리** 필요 (빈 값 또는 캐시 반환)

---

## Future Considerations

클라이언트 종류가 늘어나거나 (모바일 앱 추가 등) 홈 화면 복잡도가 높아지는 시점에
독립된 BFF 서비스 분리를 재검토한다.
