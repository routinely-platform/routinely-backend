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

#### 구현 방식: 즉시 처리(A) vs 수신·처리 분리(B)

위 4단계는 수신과 처리를 한 트랜잭션에서 끝내는 **즉시 처리(A 방식)**다.
처리 비용이 작고 부수효과가 없을 때 적합하다.

처리에 시간이 걸리거나 처리 실패를 Kafka 재처리와 분리하고 싶을 때는
**수신·처리 분리(B 방식)**를 쓴다. Consumer는 Inbox에 `RECEIVED`로 저장만 하고
즉시 오프셋을 커밋하며, 별도 스케줄러가 `RECEIVED` 건을 폴링해 비즈니스 처리를 수행한다.

| 항목 | A: 즉시 처리 | B: 수신·처리 분리 |
|---|---|---|
| Consumer 책임 | 멱등 체크 + 비즈니스 처리 + Inbox 저장 | Inbox 저장만 (`RECEIVED`) |
| 처리 주체 | Consumer 스레드 | 폴링 스케줄러 |
| 처리 실패 영향 | Kafka 오프셋 미커밋 → Kafka 재처리 | Inbox 상태로 재시도 (Kafka 무관) |
| 대칭성 | — | Outbox Poller와 대칭 |

`routine-service`의 `challenge.created` 소비(루틴 템플릿 생성)는 **B 방식**을 채택한다.
`routine_inbox`의 상태 라이프사이클(`RECEIVED → PROCESSED / FAILED`)이 B 방식을 전제로 설계되어 있고,
Kafka 소비를 템플릿 생성 지연·실패와 분리하기 위함이다 (ADR-0034).

#### Inbox 처리 측 재시도 모델

B 방식의 처리 실패는 Outbox의 `PENDING` 재시도(아래 3️⃣)와 **대칭적으로** 다룬다.
이를 위해 `routine_inbox`에 `retry_count`, `last_error` 컬럼을 둔다.

```
처리 실패
├── retry_count 증가, last_error 기록
├── retry_count ≤ MAX_RETRY(5) → RECEIVED 유지 → 다음 폴링에서 재시도
└── retry_count >  MAX_RETRY     → FAILED (영구 실패, 수동 모니터링 대상)
```

- 일시적 장애(DB 순단, 락 경합, JSON 직렬화 오류) 한 번에 영구 실패(`FAILED`)하지 않는다.
- 멱등성은 2계층으로 보장한다:
  - **수신 중복**: `message_id`(=event_id) UNIQUE — 사전 `exists` 조회로 거르고, 동시 수신 race로 저장 시점에 제약을 위반하면 예외를 잡아 무시 후 ACK
  - **처리 중복**: 도메인 UNIQUE(`routine_templates.challenge_id`) — 재시도/중복 처리 시 템플릿 재생성 방지
- 처리 스케줄러는 ShedLock(Redis)으로 멀티 인스턴스 중복 실행을 방지한다 (ADR-0033, ADR-0014).

#### 적용 사례 — 서비스별 Inbox 채택 현황

Inbox B 방식은 이제 두 서비스에서 동일한 구조로 사용된다.

| 서비스 | 소비 이벤트 | 후속 처리 | "처리 중복" 방어 방식 |
|---|---|---|---|
| routine-service | `challenge.created` | 루틴 템플릿 생성 | 도메인 UNIQUE (`routine_templates.challenge_id`) |
| challenge-service (#48) | `challenge.member.joined`, `routine.execution.completed` | 랭킹 집계 갱신 | 멱등 연산 (아래) |

`challenge_inbox`에도 `retry_count`, `last_error`를 추가해(마이그레이션 V3) `routine_inbox`와 동일한
`RECEIVED → PROCESSED / FAILED` 재시도 모델을 따른다. 처리 스케줄러(`ChallengeInboxScheduler`) 역시
ShedLock으로 멀티 인스턴스 중복 실행을 방지한다.

##### "처리 중복" 방어는 도메인마다 다르다 — UNIQUE 제약 vs 멱등 연산

Inbox의 `message_id` UNIQUE는 **수신 중복**을 막지만, 스케줄러 재시도로 같은 메시지가
**처리 단계에서 재실행**될 수 있다(예: ZSET 갱신 성공 후 트랜잭션 커밋 실패 → 다음 폴링에서 재처리).
이 "처리 중복"은 도메인 부수효과의 성격에 따라 둘 중 하나로 흡수한다.

- **DB UNIQUE 제약** — 부수효과가 "생성"인 경우(routine-service 템플릿 생성). UNIQUE 위반으로 재생성을 차단한다.
- **멱등 연산(idempotent operation)** — 부수효과가 "덮어쓰기"인 경우(challenge-service 랭킹). 연산 자체가 멱등이라 재실행이 안전하다.
  - `ZADD ranking:{challengeId} <달성률> <userId>` — 절대값 갱신이라 몇 번 실행해도 같은 값 (증분 `+1`이 아님)
  - `challenge_member_summary` UPSERT — routine-service가 계산한 최종 집계값으로 덮어쓰므로 재실행해도 동일

만약 랭킹 점수를 "완료 수 `+1`"로 설계했다면 처리 중복을 UNIQUE로 막아야 했겠지만,
점수를 "달성률 절대값으로 set"하도록 설계(ADR-0028)해 **처리 멱등성을 연산 수준에서 확보**했다.

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
- [ADR-0033: 스케줄러 분산 락](adr-0033-challenge-scheduler-distributed-lock.md) — Inbox 처리 스케줄러 중복 실행 방지
- [ADR-0034: 챌린지 생성 시 루틴 템플릿 비동기 처리](adr-0034-challenge-creation-routine-template-async.md) — Inbox B 방식 적용 사례 (routine-service)
- [ADR-0028: 이벤트 기반 집계](adr-0028-event-driven-summary-aggregation.md) — 랭킹 ZADD 절대값 설계로 처리 멱등성 확보 (challenge-service #48)
