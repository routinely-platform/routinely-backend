# ADR-0029: 챌린지 상태 자동 전이 전략

## Status
Superseded by ADR-0033

> ADR-0033으로 통합됨. 상태 전이 전략(Outbox + Kafka), 분산 락(ShedLock), 이벤트 명세가 ADR-0033에 모두 포함됨.

## Context

챌린지는 시작일/종료일 기준으로 세 가지 상태를 가진다.

```
WAITING → ACTIVE → ENDED
```

사용자 액션 없이 날짜가 되면 자동으로 상태가 바뀌어야 하며, 상태 전이 시점에 다른 서비스가 반응해야 하는 부수 처리가 존재한다.

**WAITING → ACTIVE 전이 시 필요한 후속 처리**
- RoutineService: 챌린지 기간 내 `routine_executions` 사전 생성
- NotificationService: 챌린지 시작 알림 발송
- ChatService: 채팅방 SYSTEM 메시지 발행

**ACTIVE → ENDED 전이 시 필요한 후속 처리**
- RoutineService: 미수행 `routine_executions` SKIPPED 처리 및 통계 마감
- NotificationService: 종료 알림 및 최종 달성률 안내
- ChatService: 채팅방 archive 처리

---

## Considered Options

### 방식 1: 순수 스케줄러 (Polling)

매일 정해진 시간에 배치가 돌면서 상태를 일괄 UPDATE한다.

```sql
UPDATE challenges SET status = 'ACTIVE'
WHERE status = 'WAITING' AND started_at <= CURRENT_DATE;

UPDATE challenges SET status = 'ENDED'
WHERE status = 'ACTIVE' AND ended_at < CURRENT_DATE;
```

**장점**
- 구현 단순, `@Scheduled` 한 줄로 끝

**단점**
- 상태 전이 시점에 부수 처리(이벤트 발행, 알림 등) 트리거 불가
- 실패 시 재처리 로직 별도 필요
- 이벤트 기반 설계와 맞지 않음

---

### 방식 2: Lazy 전이 (조회 시점 계산)

`status` 컬럼을 저장하지 않고 조회 시 날짜로 계산한다.

```sql
SELECT *, CASE
    WHEN CURRENT_DATE < started_at THEN 'WAITING'
    WHEN CURRENT_DATE <= ended_at  THEN 'ACTIVE'
    ELSE 'ENDED'
END AS status FROM challenges;
```

**장점**
- 배치 불필요, 항상 정확한 상태

**단점**
- "방금 ACTIVE가 됐다"는 전이 시점을 잡을 수 없어 부수 처리 불가
- 모든 조회 쿼리에 CASE 로직 필요
- `WHERE status = 'ACTIVE'` 인덱스 활용 불가

---

### 방식 3: 스케줄러 + Outbox + Kafka (선택) ✅

상태 컬럼을 유지하되, 전이 트랜잭션 안에서 도메인 이벤트를 Outbox에 함께 INSERT한다.

```
@Scheduled 배치 (매일 00:00 KST)
  └── 전이 대상 조회
       └── 트랜잭션 시작
            ├── challenges.status UPDATE
            └── challenge_outbox INSERT (ChallengeLifecycleStarted / ChallengeLifecycleEnded)
           트랜잭션 커밋

Outbox Worker → Kafka 발행
  └── challenge.lifecycle.started / challenge.lifecycle.ended

Consumer
  ├── RoutineService
  ├── NotificationService
  └── ChatService
```

**장점**
- 상태 컬럼 명시적 유지 → 인덱스 / 필터 정상 동작
- 전이 시점이 이벤트 트리거 포인트 → 부수 처리 가능
- DB 커밋과 이벤트 발행의 원자성 보장 (Outbox 패턴, ADR-0012)
- 기존 Outbox / Kafka 인프라와 일관성 유지

---

## Decision

**방식 3 (스케줄러 + Outbox + Kafka)** 을 채택한다.

---

## Implementation

### 스케줄러 주기

```java
@Scheduled(cron = "0 0 0 * * *", zone = "Asia/Seoul")  // 매일 00:00 KST
```

챌린지는 날짜 단위 도메인이므로 일 1회 자정 실행으로 충분하다.

### 분산 락 — ShedLock + Redis

Challenge 서비스를 멀티 인스턴스로 운영할 경우, 모든 인스턴스가 자정에 동시에 배치를 실행해 중복 전이가 발생할 수 있다. ShedLock을 사용해 단일 인스턴스만 실행되도록 보장한다.

```java
@SchedulerLock(
    name = "challenge-status-transition",
    lockAtMostFor = "PT10M",   // 최대 10분 락 유지 — 좀비 락 방지 TTL
    lockAtLeastFor = "PT30S"   // 최소 30초 유지 — 짧은 작업의 중복 실행 방지
)
```

Redis 키 형식: `routinely:challenge-status-transition`

- `lockAtMostFor`: 인스턴스 장애로 락이 해제되지 않을 경우를 대비한 TTL. 배치 최대 수행 시간보다 길게 설정.
- `lockAtLeastFor`: 작업이 빠르게 끝나더라도 클럭 차이로 인한 중복 실행 방지.

**ShedLock 선택 근거 (JDBC vs Redis)**

| 항목 | JDBC | Redis |
|------|------|-------|
| 별도 인프라 | 락 테이블 추가 필요 | 기존 Redis 재사용 |
| DB 부담 | 스케줄러 실행 시 DB 쿼리 발생 | 없음 |
| 운영 복잡도 | DB 마이그레이션 필요 | 없음 |

Routinely는 Redis가 이미 스택에 있으므로 Redis 기반 ShedLock을 사용한다.

### Outbox 이벤트 명세

| 이벤트 | Kafka 토픽 | 발행 조건 |
|--------|-----------|----------|
| `ChallengeLifecycleStarted` | `challenge.lifecycle.started` | WAITING → ACTIVE 전이 시 |
| `ChallengeLifecycleEnded` | `challenge.lifecycle.ended` | ACTIVE → ENDED 전이 시 |

상세 페이로드는 `docs/requirements/event-spec.md` 참조.

---

## Consequences

### Positive

- 전이 시점에 도메인 이벤트를 발행해 다른 서비스가 비동기로 반응 가능
- 기존 Outbox + Kafka 인프라와 일관성 유지
- ShedLock으로 멀티 인스턴스 환경에서도 안전하게 동작
- 상태 컬럼 유지로 인덱스 기반 조회 성능 유지

### Negative

- ShedLock 의존성 추가
- 스케줄러 실패 시 당일 전이 누락 (다음 날 자정까지 지연)
  - 완화: Outbox Worker의 재시도로 이벤트 발행 신뢰성 확보
  - 완화: 알림/모니터링으로 스케줄러 실패 감지

---

## Related

- ADR-0012: Outbox 패턴
- ADR-0008: Kafka 채택
- ADR-0013: 멱등성 전략
- `docs/requirements/event-spec.md`: `challenge.lifecycle.started` / `challenge.lifecycle.ended` 토픽 명세
