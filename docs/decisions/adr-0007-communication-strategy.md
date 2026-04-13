# ADR-0007: 서비스 간 통신 전략 정의

## Status
Accepted

## Context

Routinely는 마이크로서비스 아키텍처(MSA) 기반으로 구성된다.

다음과 같은 통신 방식이 필요하다:

1. 클라이언트 ↔ 서버 통신
2. 서비스 ↔ 서비스 동기 통신
3. 서비스 ↔ 서비스 비동기 이벤트 전달
4. 서비스 내부 비동기 작업 처리

사용 가능한 기술 선택지:

- HTTP (REST)
- gRPC
- Kafka
- PGMQ

각 통신 방식의 역할을 명확히 정의할 필요가 있었다.

---

## Decision

Routinely는 통신을 다음과 같이 구분한다:

| 목적 | 기술 |
|------|------|
| 클라이언트 ↔ 서버 (REST API) | HTTP (REST) |
| 클라이언트 ↔ 서버 (채팅 실시간) | WebSocket (STOMP) |
| 클라이언트 ↔ 서버 (알림 실시간) | SSE (Server-Sent Events) |
| Gateway → 내부 서비스 (Aggregation) | HTTP + WebClient |
| 서비스 ↔ 서비스 동기 요청 | gRPC |
| 서비스 ↔ 서비스 비동기 이벤트 | Kafka |
| 서비스 내부 비동기 작업 | PGMQ |

> WebSocket vs SSE 선택 근거는 ADR-0021 참조
> Gateway WebClient 선택 근거는 ADR-0024 참조

---

## Rationale

### 1️⃣ HTTP (REST) / WebSocket / SSE

클라이언트-서버 간 통신은 세 가지 방식을 사용한다.

**HTTP (REST)** — 일반 API 요청
- Gateway를 통해 각 서비스로 라우팅
- 표준적이고 디버깅이 용이
- 사용 예: 루틴 조회, 챌린지 생성, 채팅방 목록 조회

**WebSocket (STOMP)** — 채팅 실시간 통신
- 클라이언트가 메시지를 보내고 받아야 하는 양방향 통신 필요
- STOMP 기반 채팅방 단위 구독/발행 모델
- 사용 예: 그룹 채팅 메시지 송수신

**SSE (Server-Sent Events)** — 알림 단방향 스트리밍
- 서버 → 클라이언트 단방향 이벤트 전달
- HTTP 기반, 브라우저 자동 재연결 지원
- 사용 예: 루틴 알림, 챌린지 이벤트 알림

> WebSocket vs SSE 상세 비교는 ADR-0021 참조

---

### 2️⃣ gRPC (동기 서비스 간 통신)

- 즉시 응답이 필요한 내부 요청
- 낮은 레이턴시
- 타입 안정성 (Proto 기반)
- 명확한 계약 정의

사용 예:
- ChatService → ChallengeService: 멤버 검증
- RoutineService → ChallengeService: 챌린지 루틴 실행 검증

gRPC는 "지금 당장 결과가 필요한 요청"에만 사용한다.

---

### 3️⃣ Kafka (도메인 이벤트 전달)

- 서비스 간 느슨한 결합
- 다중 구독자(fan-out)
- 이벤트 기반 확장성 확보
- 이벤트 리플레이 가능

사용 예:

- routine.execution.done
  → NotificationService (알림 스케줄 생성)

- challenge.member.joined
  → ChatService 캐시 무효화

- chat.message.created
  → ChatService 인스턴스 간 브로드캐스트

Kafka는 "이 일이 발생했다"는 사실을 전달하는 용도로 사용한다.

---

### 4️⃣ PGMQ (서비스 내부 워크큐)

- 예약 기반(due) 처리
- 재시도 로직 구현
- 외부 API 호출 보호

사용 예:

- NotificationService
    - sendAt 기반 알림 발송
    - 실패 재시도

- 내부 후처리 작업

PGMQ는 서비스 내부 비동기 작업 전용으로 사용하며,
서비스 간 이벤트 브로커로 사용하지 않는다.

---

## Communication Flow Summary

### 동기 흐름

Client → Gateway → Service (HTTP)
Service → Service (gRPC)

### 비동기 흐름

Service → Kafka → Subscriber Services
Service 내부 → PGMQ → Worker

---

## Consequences

### Positive

- 통신 역할이 명확히 분리됨
- 서비스 간 결합도 감소
- 확장성 확보
- 유지보수 용이

### Negative

- 운영 복잡도 증가 (Kafka + PGMQ 관리)
- 이벤트 설계에 대한 추가 고려 필요
- 분산 환경에서 디버깅 난이도 증가

---

## Architectural Principle

- Command는 gRPC
- Event는 Kafka
- Job은 PGMQ
- Client 요청은 HTTP

이 원칙을 유지하여 통신 방식을 일관성 있게 관리한다.