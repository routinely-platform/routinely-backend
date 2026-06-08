# ADR-0033: 챌린지 상태 전이 스케줄러 — 전략, Outbox, 분산 락

- **Status**: Accepted
- **Date**: 2026-05-28
- **Updated**: 2026-06-07
- **Author**: Routinely Project
- **Supersedes**: ADR-0029
- **Related**: ADR-0012 (Outbox 패턴), ADR-0008 (Kafka 채택), ADR-0013 (멱등성)

---

## 1. Context

챌린지는 시작일/종료일 기준으로 세 가지 상태를 가진다.

```
WAITING → ACTIVE → ENDED
```

사용자 액션 없이 날짜가 되면 자동으로 상태가 바뀌어야 하며, 상태 전이 시점에 다른 서비스가 반응해야 하는 부수 처리가 필요하다.

**WAITING → ACTIVE 전이 시 후속 처리 (MVP)**
- RoutineService: 챌린지 기간 내 `routine_executions` 사전 생성
- NotificationService: 챌린지 시작 알림 발송
- ChatService: 채팅방 SYSTEM 메시지 발행

**ACTIVE → ENDED 전이 시 후속 처리 (v2 이상)**
- RoutineService: 미수행 `routine_executions` SKIPPED 처리 및 통계 마감
- NotificationService: 종료 알림 및 최종 달성률 안내
- ChatService: 채팅방 archive 처리

또한 challenge-service가 여러 인스턴스로 운영될 때 Spring `@Scheduled`는 **JVM 단위로 실행**된다.
인스턴스가 N개면 스케줄러도 N개가 동시에 실행되어 동일한 챌린지를 중복 조회한다.

---

## 2. 상태 전이 방식 선택

### 방식 1: 순수 스케줄러 (Polling)

매일 정해진 시간에 배치가 돌면서 상태를 일괄 UPDATE한다.

```sql
UPDATE challenges SET status = 'ACTIVE'
WHERE status = 'WAITING' AND started_at <= CURRENT_DATE;
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
            └── challenge_outbox INSERT (ChallengeStartedEvent)
           트랜잭션 커밋

Outbox Worker → Kafka 발행 → challenge.started
Consumer: RoutineService / NotificationService / ChatService
```

**장점**
- 상태 컬럼 명시적 유지 → 인덱스 / 필터 정상 동작
- 전이 시점이 이벤트 트리거 포인트 → 부수 처리 가능
- DB 커밋과 이벤트 발행의 원자성 보장 (Outbox 패턴, ADR-0012)
- 기존 Outbox / Kafka 인프라와 일관성 유지

---

## 3. 중복 실행 시 실제 문제

| 상황 | 결과 |
|---|---|
| 두 인스턴스가 동시에 `UPDATE challenges SET status='ACTIVE' WHERE status='WAITING'` | DB 트랜잭션 레벨에서 한 건만 성공 — 정합성은 보장됨 |
| 두 인스턴스가 동시에 동일 챌린지를 조회 | 불필요한 DB 읽기 부하 발생 |
| Outbox 테이블에 중복 INSERT 시도 | `idempotency_key UNIQUE` 제약으로 한 건만 성공 |

**정합성 자체는 Outbox 패턴과 DB 트랜잭션이 이미 보장한다.**
ShedLock의 목적은 정합성 확보가 아니라 **불필요한 중복 실행을 앞단에서 차단하는 최적화**다.

---

## 4. Decision

**방식 3 (스케줄러 + Outbox + Kafka) + ShedLock (Redis)** 를 채택한다.

```java
@Scheduled(cron = "0 0 0 * * *", zone = "Asia/Seoul")  // 매일 00:00 KST
@SchedulerLock(
    name = "challenge-status-transition",
    lockAtMostFor = "5m",
    lockAtLeastFor = "1m"
)
public void transitionChallengeStatus() {
    // WAITING → ACTIVE, ACTIVE → ENDED 전이 처리
}
```

실행 흐름:

```
[인스턴스 A]  Redis에 SET "challenge-status-transition" → 성공 → 스케줄러 실행
[인스턴스 B]  Redis에 SET "challenge-status-transition" → 이미 존재 → 즉시 종료
[인스턴스 C]  Redis에 SET "challenge-status-transition" → 이미 존재 → 즉시 종료

[인스턴스 A]  처리 완료 → 락 해제
```

> **ShedLock 설정값 근거**: MVP 트래픽 기준 보수적 설정. 실제 배치 수행 시간은 수 초 ~ 수십 초 예상.
> 운영 후 Grafana 배치 수행 시간 메트릭을 기반으로 재조정한다.

---

## 5. Redis를 락 저장소로 선택한 이유

ShedLock은 락 저장소로 DB(JdbcTemplate), Redis, ZooKeeper 등을 지원한다.

| 저장소 | 특징 |
|---|---|
| DB (challenge DB) | 별도 인프라 불필요. 락 테이블 추가 필요. 스케줄러와 동일 DB 사용 시 부하 공유 |
| Redis | 별도 인프라 필요. 이 프로젝트는 **이미 Redis 사용 중** (Rate Limiting, 랭킹 ZSET) |
| ZooKeeper | 별도 인프라 필요. 운영 복잡도 높음 |

Redis가 이미 인프라에 존재하므로 추가 비용 없이 활용 가능하다.

---

## 6. Outbox 이벤트 명세

| 이벤트 클래스 | Kafka 토픽 | 발행 조건 | MVP |
|--------------|-----------|----------|:---:|
| `ChallengeStartedEvent` | `challenge.started` | WAITING → ACTIVE 전이 시 | ✅ |
| `ChallengeEndedEvent` | `challenge.ended` | ACTIVE → ENDED 전이 시 | ❌ v2 |

> `ChallengeEndedEvent`는 MVP에서 발행하지 않는다.
> subscriber(최종 통계 집계, 종료 알림, 채팅방 archive)가 v2 이상에서 구현될 때 함께 활성화한다.

상세 페이로드는 `docs/requirements/event-spec.md` 참조.

---

## 7. MVP 단계 적용 여부

MVP에서 인스턴스가 1개라면 ShedLock 없이도 안전하다.
단, 다음 조건 중 하나라도 해당되면 도입을 권장한다.

- 인스턴스 2개 이상 운영
- 스케줄러 실행 시간이 1분 이상 소요될 가능성 (대량 챌린지 처리)
- 운영 환경에서 스케줄러 중복 실행 로그를 허용하지 않을 때

---

## 8. Consequences

### 긍정적 영향

- 전이 시점에 도메인 이벤트를 발행해 다른 서비스가 비동기로 반응 가능
- 기존 Outbox + Kafka 인프라와 일관성 유지
- ShedLock으로 멀티 인스턴스 환경에서도 안전하게 동작
- 상태 컬럼 유지로 인덱스 기반 조회 성능 유지
- 불필요한 중복 DB 조회 제거, 스케줄러 로그 단일화

### 부정적 영향

- ShedLock 의존성 추가
- Redis 장애 시 락 획득 실패 → 스케줄러 전체 미실행 가능
  - 완화: `lockAtMostFor` 설정으로 락이 만료되면 자동 해제. Redis 복구 후 다음 주기에 정상 실행
- 스케줄러 실패 시 당일 전이 누락 (다음 날 자정까지 지연)
  - 완화: 알림/모니터링으로 스케줄러 실패 감지

---

## 9. Related ADRs

- [ADR-0012: Outbox 패턴](adr-0012-outbox-pattern.md) — 이벤트 발행 정합성
- [ADR-0013: 멱등성 전략](adr-0013-idempotency.md)
- [ADR-0029: (Superseded)](adr-0029-challenge-status-auto-transition.md) — 본 ADR로 통합됨
