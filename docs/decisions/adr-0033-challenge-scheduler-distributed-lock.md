# ADR-0033: 챌린지 상태 전이 스케줄러 — 분산 락 (ShedLock + Redis)

- **Status**: Accepted
- **Date**: 2026-05-28
- **Author**: Routinely Project
- **Related**: ADR-0032 (챌린지 시작 시 루틴 생성 비동기 처리)

---

## 1. Context

챌린지 상태 전이 스케줄러는 매시 정각에 `WAITING → ACTIVE`, `ACTIVE → ENDED` 전이 대상 챌린지를 조회하고 처리한다.

challenge-service가 여러 인스턴스로 운영될 때 Spring `@Scheduled`는 **JVM 단위로 실행**된다.
인스턴스가 N개면 스케줄러도 N개가 동시에 실행되어 동일한 챌린지를 중복 조회한다.

---

## 2. 중복 실행 시 실제 문제

| 상황 | 결과 |
|---|---|
| 두 인스턴스가 동시에 `UPDATE challenges SET status='ACTIVE' WHERE status='WAITING'` | DB 트랜잭션 레벨에서 한 건만 성공 — 정합성은 보장됨 |
| 두 인스턴스가 동시에 동일 챌린지를 조회 | 불필요한 DB 읽기 부하 발생 |
| Outbox 테이블에 중복 INSERT 시도 | `idempotency_key UNIQUE` 제약으로 한 건만 성공 |

**정합성 자체는 Outbox 패턴과 DB 트랜잭션이 이미 보장한다.**
ShedLock의 목적은 정합성 확보가 아니라 **불필요한 중복 실행을 앞단에서 차단하는 최적화**다.

---

## 3. Decision

**ShedLock을 도입하고, 락 저장소로 Redis를 사용한다.**

```java
@Scheduled(cron = "0 0 * * * *")
@SchedulerLock(name = "challenge-status-transition", lockAtMostFor = "5m", lockAtLeastFor = "1m")
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

---

## 4. Redis를 락 저장소로 선택한 이유

ShedLock은 락 저장소로 DB(JdbcTemplate), Redis, ZooKeeper 등을 지원한다.

| 저장소 | 특징 |
|---|---|
| DB (challenge DB) | 별도 인프라 불필요. 락 테이블 추가 필요. 스케줄러와 동일 DB 사용 시 부하 공유 |
| Redis | 별도 인프라 필요. 이 프로젝트는 **이미 Redis 사용 중** (Rate Limiting, 랭킹 ZSET) |
| ZooKeeper | 별도 인프라 필요. 운영 복잡도 높음 |

Redis가 이미 인프라에 존재하므로 추가 비용 없이 활용 가능하다.

---

## 5. MVP 단계 적용 여부

MVP에서 인스턴스가 1개라면 ShedLock 없이도 안전하다.
단, 다음 조건 중 하나라도 해당되면 도입을 권장한다.

- 인스턴스 2개 이상 운영
- 스케줄러 실행 시간이 1분 이상 소요될 가능성 (대량 챌린지 처리)
- 운영 환경에서 스케줄러 중복 실행 로그를 허용하지 않을 때

---

## 6. Consequences

### 긍정적 영향

- 불필요한 중복 DB 조회 제거
- 스케줄러 실행 로그가 단일 인스턴스에서만 발생해 모니터링이 단순해짐
- 정합성 방어선이 DB 트랜잭션(1차) + ShedLock(0차)으로 이중화됨

### 부정적 영향

- Redis 장애 시 락 획득 실패 → 스케줄러 전체 미실행 가능
  - 완화: `lockAtMostFor` 설정으로 락이 만료되면 자동 해제. Redis 복구 후 다음 주기에 정상 실행
- ShedLock 의존성 추가

---

## 7. Related ADRs

- [ADR-0032: 챌린지 시작 시 루틴 생성](adr-0032-challenge-start-routine-creation.md) — 스케줄러가 발행하는 ChallengeStarted 이벤트 흐름
- [ADR-0012: Outbox 패턴](adr-0012-outbox-pattern.md) — 이벤트 발행 정합성 (ShedLock 없이도 보장되는 부분)
