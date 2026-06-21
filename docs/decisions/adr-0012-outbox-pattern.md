# ADR-0012: Outbox 패턴 도입 여부

## Status
Accepted

---

## Context

Routinely는 Kafka를 사용하여 서비스 간 도메인 이벤트를 전달한다.

예:

- `challenge.created`
- `challenge.member.joined`
- `challenge.member.left`
- `routine.execution.completed`

이벤트 발행 과정에서 다음과 같은 문제가 발생할 수 있다:

1. DB 트랜잭션은 성공했지만 Kafka publish는 실패
2. DB에는 데이터가 저장되었지만 이벤트는 전달되지 않음
3. 후속 서비스(routine-service, notification-service 등)가 동작하지 않음
4. 시스템 불일치 발생 (Inconsistent State)

Routinely는 알림, 통계, 랭킹이 핵심 기능이므로
이벤트 유실은 허용하기 어렵다.

따라서 DB 커밋과 이벤트 발행 간의 정합성을 보장할 전략이 필요했다.

---

## Decision

Routinely는 **Outbox 패턴을 도입한다.**

단, Debezium 기반 CDC 방식은 사용하지 않고
애플리케이션 레벨 Outbox (Outbox-lite) 방식을 채택한다.

---

## Why Not Direct Kafka Publish?

다음 방식은 안전하지 않다:

```java
@Transactional
public void createChallenge() {
    challengeRepository.save(...);
    kafkaTemplate.send("challenge.created", event); // DB 커밋 전 Kafka 전송 시도
}
```

- DB 커밋은 성공했지만 Kafka publish가 네트워크 오류로 실패할 수 있다.
- `@Transactional` 범위 밖에서 Kafka를 호출하면 DB 롤백과 이벤트 발행이 따로 움직인다.
- 재시도 로직이 없으면 이벤트가 영구 유실된다.

---

## Why Not CDC (Debezium)?

CDC 방식은 DB WAL(Write-Ahead Log)을 직접 구독하여 이벤트를 발행한다.

| 항목 | 이유 |
|---|---|
| 인프라 복잡도 | Debezium + Kafka Connect 클러스터 추가 운영 필요 |
| 팀 규모 | 소규모 프로젝트에서 인프라 오버헤드가 너무 큼 |
| 제어 범위 | 페이로드 가공·재시도 정책을 애플리케이션 레벨에서 직접 제어하는 것이 유연함 |

---

## Selected Approach: Application-Level Outbox

### 동작 흐름

```
비즈니스 트랜잭션
├── 도메인 데이터 저장 (challenge 등)
└── challenge_outbox 테이블에 PENDING 레코드 저장
         ↓ (같은 트랜잭션 — 원자적 보장)

ChallengeOutboxPoller (@Scheduled, 1초마다)
├── PENDING 레코드 조회 (FOR UPDATE SKIP LOCKED, 최대 100건)
├── KafkaTemplate.send().get() — 동기 발행
├── 성공 → status = PUBLISHED, publishedAt 기록
└── 실패 → retryCount 증가, 5회 초과 시 status = FAILED
```

---

## 테이블 구조 (challenge_outbox)

| 컬럼 | 타입 | 설명 |
|---|---|---|
| `id` | BIGINT PK | 자동 증가 |
| `aggregate_type` | VARCHAR(50) | 도메인 식별자 (현재: `"challenge"`) |
| `aggregate_id` | BIGINT | 챌린지 ID |
| `event_type` | VARCHAR(100) | Kafka 토픽명 (`challenge.created` 등) |
| `payload` | JSONB | 이벤트 페이로드 (JSON) |
| `status` | VARCHAR(20) | `PENDING` / `PUBLISHED` / `FAILED` |
| `created_at` | TIMESTAMP | 생성 시각 |
| `published_at` | TIMESTAMP | 발행 성공 시각 |
| `retry_count` | INT | 현재까지 재시도 횟수 |
| `idempotency_key` | VARCHAR(200) UNIQUE | 중복 발행 방지 키 |

---

## 구현 세부 사항

### 파티션 키

```java
kafkaTemplate.send(outbox.getEventType(), outbox.getAggregateId().toString(), outbox.getPayload())
```

- 파티션 키: `aggregateId` (챌린지 ID)
- 동일 챌린지의 이벤트는 동일 파티션으로 라우팅 → **이벤트 순서 보장**

### 멱등성 키 형식

```
{eventType}:{aggregateId}
예: challenge.created:42
```

- `idempotency_key`에 UNIQUE 제약 → 동일 이벤트 중복 삽입 방지

### 재시도 정책

- 최대 재시도: 5회 (`MAX_RETRY = 5`)
- 5회 초과 시 `FAILED` 처리 (수동 모니터링 대상)
- 별도 백오프 없음 — 1초 폴링 주기가 자연적 지연 역할

### 동시성 제어

```sql
SELECT * FROM challenge_outbox
WHERE status = 'PENDING'
ORDER BY created_at ASC, id ASC
LIMIT :limit
FOR UPDATE SKIP LOCKED
```

- 멀티 인스턴스 배포 시 동일 레코드 중복 처리 방지
- `SKIP LOCKED`: 다른 트랜잭션이 잠근 레코드는 건너뜀

---

## Consequences

### Positive

- DB 커밋과 Kafka 발행이 원자적으로 묶임 → **이벤트 유실 없음**
- 재시도 로직 내재화 → 네트워크 일시 장애에 자동 복구
- `idempotency_key` UNIQUE 제약으로 중복 삽입 방지
- `FOR UPDATE SKIP LOCKED`로 멀티 인스턴스 안전하게 운영 가능

### Negative

- DB에 outbox 테이블 추가 → 쓰기 부하 증가 (비즈니스 트랜잭션마다 row 2개)
- `FAILED` 레코드 수동 처리 필요 (알림/모니터링 미구현 시 유실 위험)
- 폴링 방식이므로 발행 지연 최대 1초 존재

---

## Related

- ADR-0008: Kafka 도입 결정
- ADR-0013: 멱등성 전략 (Inbox 패턴)
- ADR-0014: Kafka 소비자 복원력 전략
