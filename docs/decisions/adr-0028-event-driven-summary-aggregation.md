# ADR-0028: 이벤트 기반 집계 (CQRS) — ADR-0011 번복

- **Status**: Accepted
- **Date**: 2026-05-21
- **Author**: Routinely Project
- **Supersedes**: ADR-0011 (통계 집계 전략 — On-Demand 직접 SQL 집계)

---

## 1. Context

ADR-0011에서는 MVP 단계에서 `routine_executions`를 매 요청마다 직접 집계하는 방식을 채택했다.
이후 두 가지 요인이 추가되면서 재검토가 필요해졌다.

1. **달성률 캡 계산 복잡도 증가** (ADR-0027): `WEEKLY_N`/`MONTHLY_N`에서 주별/월별 캡을 적용하려면
   집계 쿼리가 단순 GROUP BY를 넘어 CTE + LEAST() 조합이 필요하다.
   이를 랭킹 조회마다 실행하면 멤버 수 × 쿼리 비용이 즉각적으로 발생한다.

2. **랭킹 Redis ZSET 동기화 필요**: `challenge_member_summary.achievement_rate`를
   Redis Sorted Set에 반영하려면 어차피 "완료 이벤트 발생 시점"에 갱신 트리거가 필요하다.
   On-Demand 구조에서는 Redis 동기화 시점을 자연스럽게 결정하기 어렵다.

---

## 2. Decision

**루틴 완료 처리 시 `RoutineCompleted` 이벤트를 발행하고, Consumer가 summary 테이블을 갱신한다.**

ADR-0011의 "요청마다 직접 SQL 집계" 방식을 폐기한다.

### 흐름

```
[routine-service]
루틴 완료 처리 (POST /routine-executions/{id}/complete)
  ├── routine_executions UPDATE (status = COMPLETED)
  └── routine_outbox INSERT (event_type = routine.execution.completed)

[Outbox Worker → Kafka]
  └── routine.execution.completed 발행

[routine-service Consumer]
  └── routine_daily_summary UPSERT (캡 계산 후 accepted_count, achievement_rate 갱신)

[challenge-service Consumer]
  ├── challenge_member_summary UPSERT (accepted_count, achievement_rate 갱신)
  └── Redis ZSET UPDATE (key: ranking:{challengeId}, score: achievement_rate, member: userId)
```

### 조회

- **개인 통계**: `routine_daily_summary`에서 O(1) 조회
- **챌린지 랭킹**: Redis ZSET에서 O(log N) 조회 (fallback: `challenge_member_summary`)

---

## 3. Rationale

### 3.1 집계 비용을 쓰기 시점으로 이동

랭킹 조회는 조회 빈도가 쓰기 빈도보다 월등히 높다.
캡 계산 쿼리를 조회마다 수행하면 멤버 전원의 주별 집계를 반복적으로 계산해야 한다.
쓰기(완료 처리) 시점에 한 번 계산하고 저장해두면 조회는 O(1)이 된다.

### 3.2 Redis 동기화의 자연스러운 트리거

루틴 완료 이벤트가 발생하는 시점이 곧 "랭킹 갱신이 필요한 시점"이다.
이벤트 기반으로 연결하면 별도 스케줄러 없이 실시간에 가까운 랭킹 갱신이 가능하다.

### 3.3 ADR-0011 채택 당시와 조건 변화

ADR-0011 채택 이유 중 "집계 쿼리가 단순하다"는 전제가 ADR-0027(캡 계산) 도입으로 깨졌다.
단순 GROUP BY → CTE + LEAST() 조합으로 복잡도가 증가했으므로 결정을 번복한다.

---

## 4. Consumer 그룹 목록

`routine.execution.completed` 토픽의 소비자:

| Consumer Group | 서비스 | 처리 내용 |
|---|---|---|
| `routine-service.routine.execution.completed` | routine-service | `routine_daily_summary` UPSERT |
| `challenge-service.routine.execution.completed` | challenge-service | `challenge_member_summary` UPSERT + Redis ZSET 갱신 |
| `notification-service.routine.execution.completed` | notification-service | 스트릭/완료 알림 판단 (기존 유지) |

### 4.1 `challenge.member.joined` 자기 소비 — 랭킹 시드 (#48)

challenge-service는 **자신이 발행한** `challenge.member.joined` 이벤트를 **자신이 다시 소비**한다.
멤버가 참여하면 아직 완료 기록이 없어도 달성률 0%인 랭킹 행이 즉시 노출되어야 하기 때문에,
소비 시점에 `challenge_member_summary`를 생성하고 Redis ZSET에 `0`점으로 시드(seed)한다.

| Consumer Group | 서비스 | 처리 내용 |
|---|---|---|
| `challenge-service.ranking.member.joined` | challenge-service (자기 소비) | `challenge_member_summary` 생성 + Redis ZSET `0`점 시드 |

**왜 참여 트랜잭션에서 바로 처리하지 않고 이벤트로 우회하는가:**

1. **집계 경로 단일화** — 랭킹 갱신을 담당하는 `ChallengeRankingInboxProcessor` 입장에서는
   이벤트가 자기 것(`member.joined`)이든 남의 것(`routine.execution.completed`)이든 동일하게 취급한다.
   참여만 동기 직접 호출로 처리하면 "랭킹을 갱신하는 방법"이 동기·비동기 두 갈래로 이원화되어
   유지보수 지점이 늘어난다. 자기 이벤트도 같은 Inbox 파이프라인에 태워 **단일 경로**로 유지한다.

2. **실패 격리** — 참여(멤버십 변경)는 DB 트랜잭션만으로 빠르게 확정하고, 랭킹 반영(Redis/summary)은
   별도 스케줄러가 독립적으로 재시도(최대 5회, 초과 시 `FAILED`)한다.
   join 트랜잭션 안에서 Redis를 직접 건드리면 Redis 지연·장애가 "챌린지 참여"라는 핵심 액션 자체를
   지연·실패시킬 수 있으나, 이벤트로 분리하면 참여 확정에는 영향이 없다.

3. **처리 멱등성** — 시드는 "이미 summary가 있으면 건너뛴다"로 처리한다.
   참여 후 완료 기록이 쌓여 달성률이 오른 멤버가 이벤트 재처리로 0%로 리셋되면 안 되므로,
   재시드 시 기존 값을 덮어쓰지 않고 스킵하는 것이 핵심이다.

---

## 5. Consequences

### 긍정적 영향

- 랭킹/통계 조회 성능 O(N·집계쿼리) → O(1) 개선
- Redis ZSET 동기화 시점이 명확해짐
- 캡 계산을 쓰기 시점에 한 번만 수행

### 부정적 영향

- 완료 처리 → 이벤트 발행 → Consumer 처리까지 짧은 지연 존재 (통상 수백 ms 이내)
- Consumer 장애 시 summary가 일시적으로 오래된 값을 가질 수 있음
- 멱등성 처리 필요 (Inbox 테이블로 보장 — ADR-0013)

### 수용 가능 여부

랭킹/통계는 수백 ms 수준의 최종 일관성(eventual consistency)이 허용 가능한 데이터이므로
위 단점은 수용 가능하다.

---

## 6. 관련 결정

- ADR-0011: 번복된 원결정 (직접 SQL 집계)
- ADR-0012: Outbox 패턴 (이벤트 발행 정합성 보장)
- ADR-0013: 멱등성 전략 (Consumer 중복 처리 방지)
- ADR-0027: 달성률 캡 계산 (이 집계 방식의 계산 공식)
