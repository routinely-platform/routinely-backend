# ADR-0013: 멱등성 전략 (Idempotency Strategy)

## Status

Accepted

---

## Context

Routinely는 다음과 같은 비동기 및 분산 환경 요소를 포함한다:

- Kafka 기반 이벤트 전달
- Outbox 패턴 기반 이벤트 발행
- PGMQ 기반 내부 비동기 작업
- 멀티 인스턴스 확장 가능성
- 재시도 정책(backoff, retry)

이 환경에서는 다음 상황이 발생할 수 있다:

1. Kafka 메시지 중복 전달 (At-Least-Once)
2. Consumer 재시작으로 인한 재처리
3. PGMQ 메시지 재시도
4. 네트워크 타임아웃으로 인한 중복 요청
5. Outbox publish 재시도로 인한 중복 발행

따라서 시스템은 **중복 처리에 안전해야 한다.**

---

## Decision

Routinely는 "At-Least-Once Delivery"를 전제로 하며,
애플리케이션 레벨에서 멱등성을 보장한다.

> 동일 이벤트/요청이 여러 번 처리되어도
> 시스템 상태가 한 번 처리된 것과 동일하게 유지되어야 한다.

Kafka Consumer 측 구체적인 복원력 전략(@RetryableTopic, Inbox, DLT)은 `adr-0012` 참고.

---

## Core Principles

1. Kafka는 Exactly-Once를 가정하지 않는다.
2. PGMQ는 중복 소비 가능성을 허용한다.
3. 모든 이벤트 소비자는 멱등성을 고려하여 설계한다.
4. 멱등성은 "데이터 모델 수준"에서 보장한다.

---

## Idempotency Strategy by Layer

### 1️⃣ RoutineExecution (핵심 도메인)

루틴 실행은 다음 DB Unique Constraint로 중복을 방지한다:

```sql
UNIQUE (template_id, user_id, exec_date)
```

동일 사용자가 같은 날 같은 루틴을 두 번 완료 처리하려 하면 DB 레벨에서 차단된다.

---

### 2️⃣ Kafka Consumer — Inbox 패턴

Kafka Consumer는 `inbox` 테이블에 event_id를 기록하여 중복 처리를 방지한다.

```
1. 이벤트 수신
2. inbox 테이블에서 event_id 조회
3. 이미 존재 → 스킵 (중복)
4. 존재하지 않음 → 비즈니스 로직 처리 + inbox 저장 (같은 트랜잭션)
```

이벤트에는 반드시 고유한 `event_id`(UUID)가 포함되어야 한다.

---

### 3️⃣ Outbox Worker — PENDING 상태 재시도

Outbox Worker는 ACK를 받은 경우에만 outbox 상태를 SENT로 변경한다.
ACK 실패 시 PENDING 상태를 유지하여 다음 polling에서 재시도한다.

동일 이벤트가 중복 발행될 수 있지만, Consumer 측 Inbox 패턴이 이를 방어한다.

---

### 4️⃣ PGMQ 내부 작업

PGMQ는 visibility timeout 기반으로 동작한다.
처리 중 실패 시 메시지를 삭제하지 않아 vt 만료 후 자동 재등장한다.
소비자는 schedule 상태 확인(PENDING 여부)으로 중복 처리를 방어한다.

상세 내용은 `adr-0011` 참고.

---

## Consequences

### Positive

- DB Unique Constraint로 핵심 도메인 중복 방지 확보
- Inbox 패턴으로 Kafka 이벤트 중복 처리 방어
- PGMQ visibility timeout으로 내부 작업 재시도 안전성 확보

### Negative

- Consumer 서비스마다 inbox 테이블 관리 필요
- 이벤트에 반드시 고유한 event_id 포함 필요
- 멱등성 설계가 모든 이벤트 소비자에게 일관되게 적용되어야 함

---

## Related ADRs

- [ADR-0012: Outbox 패턴](adr-0012-outbox-pattern.md) — Producer 측 이벤트 발행 정합성
- [ADR-0017: 알림 스케줄링](adr-0017-notification-scheduling-strategy.md) — PGMQ 멱등성 처리
- [ADR-0014: Kafka Consumer 복원력](adr-0014-kafka-consumer-resilience-strategy.md) — Inbox, @RetryableTopic, DLT 상세
