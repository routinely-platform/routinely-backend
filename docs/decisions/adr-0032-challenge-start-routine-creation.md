# ADR-0032: 챌린지 시작 시 루틴 생성 — 비동기 처리 (Outbox + Kafka + Inbox)

- **Status**: Accepted
- **Date**: 2026-05-28
- **Author**: Routinely Project

---

## 1. Context

챌린지 참여자의 `routines`와 `routine_executions`를 언제, 어떻게 생성할지 결정이 필요했다.

고려한 선택지는 두 가지였다.

1. **참여 시점** — 사용자가 챌린지에 참여할 때 즉시 생성
2. **시작 시점** — 챌린지가 `WAITING → ACTIVE`로 전이될 때 일괄 생성

생성 방식도 두 가지를 검토했다.

- **동기 방식** — gRPC 또는 HTTP로 routine-service에 직접 요청
- **비동기 방식** — Outbox + Kafka + Inbox 패턴

---

## 2. Decision

**챌린지 시작 시점(WAITING → ACTIVE 전이)에 일괄 생성하며, Outbox + Kafka + Inbox 비동기 방식으로 처리한다.**

---

## 3. 생성 시점 — 왜 참여 시점이 아닌 시작 시점인가

| 기준 | 참여 시점 | 시작 시점 (채택) |
|---|---|---|
| `routine_executions` 필요 여부 | 챌린지 시작 전이므로 불필요 | 시작과 동시에 필요 |
| 도메인 일관성 | 비어 있는 루틴 상태 발생 | 생성과 활성화가 동시에 일어남 |
| 루틴 기간 확정 여부 | 시작일 전이므로 기간이 유동적 | 시작일 = 루틴 기간 시작점으로 확정 |

참여 시점 생성은 "참여했지만 시작 전이라 비어 있는 routine" 중간 상태를 만든다.
시작 시점 생성은 챌린지 활성화와 루틴 준비가 도메인적으로 한 번에 일어난다.

---

## 4. 처리 방식 — 왜 비동기인가

### 동기 방식과의 트레이드오프

| 항목 | 동기 방식 (gRPC/HTTP) | 비동기 방식 (Outbox + Kafka, 채택) |
|---|---|---|
| 루틴 생성 지연 | 없음 (즉시) | 수 초 이내 (Outbox poller + Kafka 지연) |
| 스케줄러 트랜잭션 | 루틴 생성까지 묶임 → 트랜잭션 길어짐 | 상태 전이 + Outbox INSERT만 포함 → 짧음 |
| routine-service 장애 영향 | 챌린지 상태 전이 자체가 실패 | 챌린지 상태 전이는 성공, 루틴 생성만 지연 |
| 다수 챌린지 동시 시작 시 | N챌린지 × M멤버 × gRPC 호출 집중 | Kafka 비동기 분산 처리 |
| 장애 격리 | 낮음 (routine-service 장애 = challenge-service 장애) | 높음 (서비스 독립 장애 허용) |

### 비동기 지연의 수용 가능성

- 실제 지연: 수 초 이내 (Outbox poller 주기 1초 이하 + Kafka 수십~수백 ms)
- 챌린지 시작은 자정/매시 정각 트리거 — 사용자가 그 시점에 화면을 보고 있을 가능성이 낮음
- 사용자는 보통 시작일 아침에 앱을 열어 인증 → 루틴 준비에 충분한 시간
- 지연 노출 시 클라이언트 안내 메시지 또는 자동 재조회로 보완

---

## 5. 전체 처리 흐름

```
[Challenge 서비스 스케줄러 — 매시 정각, ShedLock + Redis]
1. WAITING → ACTIVE 전이 대상 챌린지 조회 (FOR UPDATE SKIP LOCKED)
2. 트랜잭션 시작
   a. UPDATE challenges SET status = 'ACTIVE'
   b. INSERT challenge_outbox (event_type = 'challenge.started', 참여자 userId 리스트 포함)
3. 트랜잭션 커밋
         ↓
[Challenge 서비스 Outbox Poller]
challenge_outbox WHERE status = 'PENDING' → Kafka 발행
→ status = 'PUBLISHED' UPDATE
         ↓
[Kafka topic: challenge.started]
         ↓
[Routine 서비스 Inbox Consumer]  ← 빠른 수신만 담당
1. Kafka 메시지 수신
2. INSERT routine_inbox (status = 'PENDING')
   → message_id UNIQUE 충돌 시: 중복 수신으로 판단, ack 후 종료
3. Kafka offset commit
         ↓
[Routine 서비스 Inbox Processor]  ← 비즈니스 처리, 자기 속도로
1. SELECT FROM routine_inbox WHERE status = 'PENDING' FOR UPDATE SKIP LOCKED
2. UPDATE status = 'PROCESSING'
3. 트랜잭션 시작
   a. 페이로드 파싱 (members 리스트)
   b. 각 멤버별 routines INSERT — (challenge_id, user_id) UNIQUE로 중복 방지
   c. repeat_type = 'DAILY'인 경우 routine_executions 사전 생성 (전 기간 PENDING)
   d. UPDATE routine_inbox status = 'PROCESSED', processed_at = now()
4. 트랜잭션 커밋

실패 시:
- retry_count 증가, last_error 기록
- N회 초과 시 status = 'FAILED' → 운영 알림
         ↓
[Notification 서비스 Consumer]
challenge.started 이벤트 수신 → 멤버 전원에게 "챌린지 시작" 알림 발송
```

---

## 6. Inbox 패턴 채택 이유 (수신과 처리 분리)

> **폴링 위치 정리**: Outbox Poller(Challenge 서비스)와 Inbox Processor(Routine 서비스) 두 곳에서만 폴링이 발생한다.
> Inbox Consumer는 Kafka 이벤트 드리븐이므로 폴링이 없다.

Routine 서비스는 이벤트 수신 즉시 비즈니스 로직을 처리하지 않고, `routine_inbox` 테이블에 먼저 적재한 뒤 별도 Processor가 처리한다.

**수신과 처리를 분리한 이유**

- 자정 동시 시작 챌린지가 많을 때 단일 Consumer 트랜잭션이 길어지면 Kafka session timeout 위험 → 수신 트랜잭션을 짧게 유지
- 처리 실패 시 retry_count + status 기반 재시도가 Kafka 재전송 의존보다 제어하기 쉬움
- `routine_inbox` 테이블 자체가 운영 가시성 제공 (status별 카운트 모니터링)
- challenge_outbox ↔ routine_inbox 대칭 구조로 이해와 유지보수가 직관적

---

## 7. ChallengeStarted 이벤트 페이로드

Routine 서비스가 추가 RPC 없이 자기 완결적으로 처리할 수 있도록 필요한 모든 정보를 담는다.

```json
{
  "eventId": "550e8400-e29b-41d4-a716-446655440005",
  "occurredAt": "2026-03-01T00:00:00Z",
  "challengeId": 10,
  "startedAt": "2026-03-01",
  "endedAt": "2026-03-31",
  "routineTemplateId": 5,
  "members": [
    { "userId": 1, "joinedAt": "2026-02-25" },
    { "userId": 2, "joinedAt": "2026-02-26" }
  ]
}
```

> `eventType` 필드는 포함하지 않는다. Kafka 토픽 이름이 이벤트 타입을 식별하므로 페이로드 중복이다.

전체 명세는 `docs/requirements/event-spec.md` — `challenge.started` 섹션 참고.

---

## 8. 인덱스 설계

폴링이 발생하는 Outbox Poller와 Inbox Processor 모두 PostgreSQL Partial Index로 빈 폴링 비용을 최소화한다.

### Outbox 테이블 (challenge_outbox, routine_outbox, chat_outbox)

폴링 쿼리: `SELECT * FROM {outbox} WHERE status = 'PENDING' ORDER BY created_at LIMIT N`

```sql
-- Outbox Poller 미발행 건 폴링용
CREATE INDEX idx_{service}_outbox_pending ON {outbox} (created_at) WHERE status = 'PENDING';
```

PENDING 건만 인덱스에 포함되므로 PUBLISHED 레코드가 누적되어도 폴링 성능에 영향 없다.
Partial Index 적용 불가 환경이면 `(status, created_at)` 복합 인덱스로 대체한다.

### Inbox 테이블 (challenge_inbox, routine_inbox, chat_inbox)

두 종류의 인덱스가 필요하다.

```sql
-- (a) 멱등성 체크 — Consumer가 중복 수신 차단 (Kafka at-least-once 환경)
CONSTRAINT uq_{service}_inbox_message_id UNIQUE (message_id)

-- (b) 미처리 건 폴링 — Inbox Processor가 PENDING 건 조회
--     수신(Consumer)과 처리(Processor)를 분리하는 패턴을 채택했기 때문에 필요
CREATE INDEX idx_{service}_inbox_pending ON {inbox} (received_at) WHERE status = 'PENDING';
```

(a) `message_id UNIQUE`는 Kafka 중복 수신을 막는 것이고,
(b) PENDING Partial Index는 Inbox Processor가 미처리 건을 찾기 위한 별도 목적이다.
단순 인라인 처리(Consumer에서 직접 비즈니스 로직 실행)였다면 (b) 인덱스는 불필요하다.

---

## 9. 멱등성 및 안전장치

| 장치 | 위치 | 역할 |
|---|---|---|
| `message_id UNIQUE` | {service}_inbox | 중복 수신 차단 (Kafka at-least-once 멱등성) |
| `(challenge_id, user_id) UNIQUE INDEX` | routines | 중복 routine INSERT 차단 |
| `inbox status + retry_count 기반 재시도` | Inbox Processor | Kafka 재전송 비의존 |
| `retry_count + last_error` | {service}_inbox | 영구 실패(FAILED) 추적, DLQ 후보 |
| `routine_outbox INSERT을 Inbox Processor와 같은 트랜잭션에` | Inbox Processor | 후속 이벤트 발행 원자성 |
| Consumer lag / 처리 지연 메트릭 | Grafana | 발행 → 루틴 생성 완료 평균 시간 메트릭 |

---

## 10. 사용자 경험 보완

- 챌린지 시작 직후 routine 미생성 상태면 클라이언트에서 "챌린지를 준비 중입니다" 안내 또는 자동 폴링 (수 초 후 재조회)
- 대부분의 경우 두 번째 시도에서 정상 조회됨

---

## 11. Consequences

### 긍정적 영향

- Challenge 서비스의 상태 전이 스케줄러가 routine-service 장애로부터 격리됨
- 동시 다수 챌린지 시작 시 Kafka 비동기 분산 처리로 부하 평탄화
- Inbox 테이블이 운영 가시성 및 재처리 기반 제공

### 부정적 영향

- 챌린지 시작 ~ 루틴 생성 완료 사이 짧은 최종 일관성 지연 존재
- Inbox Processor 장애 시 루틴 생성이 지연될 수 있음 (retry로 복구)

### 수용 판단

루틴 생성은 자정 스케줄러 트리거이며 사용자가 즉시 확인할 가능성이 낮다.
수 초 이내 지연은 클라이언트 보완책으로 흡수 가능하므로 수용 가능하다.

---

## 12. Related ADRs

- [ADR-0012: Outbox 패턴](adr-0012-outbox-pattern.md) — Producer 측 이벤트 발행 정합성
- [ADR-0013: 멱등성 전략](adr-0013-idempotency-strategy.md) — Inbox 기반 중복 처리 방지
- [ADR-0014: Kafka Consumer 복원력](adr-0014-kafka-consumer-resilience-strategy.md) — Inbox, @RetryableTopic, DLT
- [ADR-0036: 챌린지 루틴 고정 정책](adr-0036-challenge-routine-fixed-policy.md) — 챌린지 루틴 템플릿 1개 고정
