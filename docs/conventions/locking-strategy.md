# 락 전략 (Locking Strategy)

challenge-service에서 사용하는 세 가지 락 기법과 각각의 적용 시점을 정리한다.

---

## 1. 비관적 락 (Pessimistic Lock)

### 목적

**동시 요청 간 경쟁 조건(Race Condition) 방지**

여러 사용자가 동시에 동일 챌린지에 참여 요청을 보낼 때,
`maxMembers` 정원 초과 여부를 조회하고 참여 처리하는 두 단계 사이에 틈이 생긴다.
이 틈을 없애기 위해 조회 시점에 행(row)을 잠근다.

### 적용 위치

```
ChallengeRepository.findByIdForUpdate()
  └── ChallengeService.joinChallenge()
```

### 코드 패턴

```java
// Repository
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("SELECT c FROM Challenge c WHERE c.id = :challengeId")
Optional<Challenge> findByIdForUpdate(@Param("challengeId") Long challengeId);

// Service — 반드시 @Transactional 안에서 호출
@Transactional
public ChallengeMemberResult joinChallenge(Long challengeId, Long userId) {
    Challenge challenge = findChallengeByIdForUpdateOrThrow(challengeId); // SELECT FOR UPDATE
    validateCapacity(challengeId, challenge.getMaxMembers());             // 정원 체크
    // ... 참여 처리
}
```

> `SELECT FOR UPDATE`는 `@Transactional` 범위 안에서만 유효하다.
> 트랜잭션 없이 호출하면 락이 즉시 해제되어 무의미해진다.

### 적용 기준

| 조건 | 비관적 락 사용 여부 |
|---|:---:|
| 조회 → 조건 검사 → 쓰기 순서로 경쟁이 발생할 수 있는 단일 row 변경 | ✅ |
| 단순 읽기 전용 조회 | ❌ |
| 배치성 일괄 처리 (스케줄러) | ❌ (분산 락 사용) |

---

## 2. 분산 락 (Distributed Lock — ShedLock + Redis)

### 목적

**멀티 인스턴스 환경에서 스케줄러 중복 실행 방지**

Spring `@Scheduled`는 JVM 단위로 실행된다.
인스턴스 N개가 떠 있으면 스케줄러도 N번 실행된다.
Redis를 공유 저장소로 사용해 선착순 1개 인스턴스만 실행되도록 막는다.

### 적용 위치

```
ChallengeStatusTransitionScheduler.transitionChallengeStatus()
```

### 코드 패턴

```java
// 설정
@Configuration
@EnableSchedulerLock(defaultLockAtMostFor = "5m")
public class ShedLockConfig {
    @Bean
    public RedisLockProvider lockProvider(RedisConnectionFactory connectionFactory) {
        return new RedisLockProvider(connectionFactory, "routinely"); // Redis key namespace
    }
}

// 스케줄러
@Scheduled(cron = "0 0 0 * * *", zone = "Asia/Seoul")
@SchedulerLock(
    name = "challenge-status-transition",  // 락 이름 = Redis key 식별자
    lockAtMostFor = "5m",                  // 프로세스 강제 종료 시 이 시간 후 자동 해제
    lockAtLeastFor = "1m"                  // 너무 빨리 끝나도 중복 실행 방지
)
@Transactional
public void transitionChallengeStatus() { ... }
```

### lockAtMostFor / lockAtLeastFor 설정 근거

| 파라미터 | 역할 | 현재 값 | 근거 |
|---|---|:---:|---|
| `lockAtMostFor` | 프로세스 죽어도 이 시간 후 자동 해제 | `5m` | 배치 수행 시간 상한선 (보수적 설정) |
| `lockAtLeastFor` | 배치가 빨리 끝나도 이 시간은 유지 | `1m` | 시계 오차 / 재시작 직후 중복 방지 |

> 운영 후 Grafana 배치 수행 시간 메트릭을 보고 재조정한다.

### 방어 계층

```
[1차] ShedLock (Redis)       — 중복 실행 자체를 앞단에서 차단 (최적화)
[2차] DB 트랜잭션             — status UPDATE 정합성 보장
[3차] idempotency_key UNIQUE — Outbox 중복 INSERT 물리적 차단 (하단 참조)
```

ShedLock은 정합성 보장보다 **불필요한 중복 실행을 없애는 최적화** 목적이다.
Redis 장애로 락을 못 얻더라도, 2차·3차 방어로 데이터 정합성은 유지된다.

---

## 3. 멱등성 키 (Idempotency Key — DB UNIQUE 제약)

### 목적

**Outbox 테이블의 중복 레코드 물리적 차단**

스케줄러나 API 요청이 어떤 이유로 두 번 실행되더라도,
동일한 이벤트를 Outbox에 두 번 INSERT하지 못하도록 막는다.

### 적용 위치

```
challenge_outbox.idempotency_key (UNIQUE)
  └── ChallengeOutbox.create(...)
```

### 키 설계 기준

`idempotencyKey = {토픽}:{구분자...}`

| 이벤트 | idempotencyKey 구성 | 이유 |
|---|---|---|
| `challenge.started` | `challenge.started:{challengeId}` | 챌린지는 딱 한 번만 시작됨 — `occurredAt` 불필요 |
| `challenge.member.joined` | `challenge.member.joined:{challengeId}:{userId}:{occurredAt}` | 재참여 허용 — 시간으로 구분 필요 |
| `challenge.member.left` | `challenge.member.left:{challengeId}:{userId}:{occurredAt}` | 재탈퇴 허용 — 시간으로 구분 필요 |

> `challenge.started`에 `occurredAt`을 넣지 않는 이유:
> 타임스탬프를 포함하면 스케줄러가 두 번 실행될 때 시간 차이만큼 키가 달라져 UNIQUE 제약을 우회할 수 있다.
> 챌린지 ID만으로 키를 구성하면 어떤 경로로 실행되더라도 두 번째 INSERT는 물리적으로 차단된다.

---

## 4. "챌린지 참여는 왜 분산 락이 아닌 비관적 락인가?"

**비관적 락(`SELECT FOR UPDATE`)은 이미 분산 환경에서 동작한다.**

`SELECT FOR UPDATE`는 **PostgreSQL 행(row)** 을 잠근다.
DB는 모든 인스턴스가 공유하는 단 하나의 저장소이므로,
인스턴스 A가 `challenge #1` 행을 잠그면 인스턴스 B·C도 동일 행 접근 시 대기한다.
"분산"이라는 단어가 붙지 않아도, **DB가 락 저장소 역할을 하기 때문에 전 인스턴스에 걸쳐 동작**한다.

반면 ShedLock이 필요한 경우는 **DB에 닿기 전 단계에서 실행 자체를 막아야 할 때**다.

```
챌린지 참여 (joinChallenge)
  └── 요청마다 특정 challenge ID가 명확함
       └── SELECT FOR UPDATE (challenge #42 행 잠금)
            → 다른 인스턴스의 같은 요청은 DB 레벨에서 대기
            → 트랜잭션 종료 시 자동 해제

챌린지 상태 전이 스케줄러 (transitionChallengeStatus)
  └── "오늘 전이 대상 전체"를 조회해 일괄 처리
       └── 잠글 특정 row가 없음
            → 인스턴스 N개가 동시에 "전체 조회 → 전체 처리" 실행
            → ShedLock으로 실행 자체를 1개 인스턴스로 제한
```

| | 비관적 락 | 분산 락 (ShedLock) |
|---|---|---|
| 잠금 저장소 | PostgreSQL (모든 인스턴스 공유) | Redis (모든 인스턴스 공유) |
| 잠금 대상 | **특정 row** | **메서드 실행 단위 전체** |
| 자동 해제 | 트랜잭션 커밋/롤백 시 | `lockAtMostFor` 만료 시 |
| 챌린지 참여 | ✅ 잠글 row가 명확 | 불필요 (추가 Redis 왕복만 발생) |
| 스케줄러 | ❌ 잠글 row가 없음 | ✅ 실행 자체를 막아야 함 |

> 챌린지 참여에 Redis 분산 락을 쓰면 오히려 단점이 생긴다.
> 매 참여 요청마다 Redis 왕복이 추가되고, 락 이름을 `challenge:{id}` 형태로 동적 관리해야 하며,
> 트랜잭션과 별개로 락 해제 타이밍을 수동 관리해야 한다.
> DB 행 잠금으로 충분한 곳에서는 DB 행 잠금을 쓰는 것이 맞다.

---

## 5. 기법 선택 요약

| 상황 | 사용할 기법 |
|---|---|
| HTTP 요청 — 동시 사용자 간 단일 row 경쟁 | 비관적 락 (`SELECT FOR UPDATE`) |
| 스케줄러 — 멀티 인스턴스 중복 실행 | 분산 락 (ShedLock + Redis) |
| Outbox — 이벤트 중복 발행 방지 | 멱등성 키 (DB UNIQUE 제약) |

---

## 5. 관련 문서

- [ADR-0033: 챌린지 상태 전이 스케줄러 — 전략, Outbox, 분산 락](../decisions/adr-0033-challenge-scheduler-distributed-lock.md)
- [ADR-0013: 멱등성 전략](../decisions/adr-0013-idempotency-strategy.md)
- [Outbox 패턴 컨벤션](outbox-pattern.md)
