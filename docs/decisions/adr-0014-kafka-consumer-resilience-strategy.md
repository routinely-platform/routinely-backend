# ADR-0014: Kafka 이벤트 발행-소비 복원력 전략

## Status
Accepted

---

## Context

Routinely는 서비스 간 비동기 이벤트 전달에 Kafka를 사용하며,
Producer 측에서는 Outbox 패턴으로 DB 커밋과 이벤트 발행의 정합성을 보장한다. (ADR-0006 참고)

그러나 다음 문제들이 남아 있다:

**Producer 측:**

1. **Kafka 발행 실패** — Outbox Worker가 이벤트를 Kafka에 발행했지만, 실제로는 Broker에 도달하지 못한 경우. Outbox 테이블에서 조회하여 발행을 시도한 뒤 상태를 SENT로 변경하면, 실패한 이벤트는 더 이상 재시도되지 않는다.

**Consumer 측:**

2. **메시지 처리 실패** — Consumer가 이벤트를 수신했지만 처리 중 예외가 발생하면 이벤트가 유실될 수 있다.
3. **중복 수신** — Kafka는 At-Least-Once 전달을 보장하므로, 동일 메시지가 여러 번 전달될 수 있다.
4. **최종 실패 처리** — 재시도를 반복해도 실패하는 메시지에 대한 처리 전략이 필요하다.

Producer(Outbox)가 "이벤트를 반드시 발행"하는 것을 보장한다면,
Consumer는 "이벤트를 반드시, 그리고 정확히 한 번 처리"하는 것을 보장해야 한다.

---

## Decision

Routinely는 Kafka 이벤트 파이프라인의 복원력을 위해 다음 4가지 전략을 조합한다:

**Producer 측:**
1. **Outbox Worker 비동기 ACK** — Kafka Broker의 ACK를 확인한 후에만 outbox 상태 변경

**Consumer 측:**
2. **@RetryableTopic** — 처리 실패 시 자동 재시도
3. **Inbox 패턴** — 중복 메시지 처리 방지 (멱등성 보장)
4. **DLT (Dead Letter Topic)** — 최종 실패 메시지 격리 및 수동 처리

---

## 1. Outbox Worker 비동기 ACK — Kafka 발행 보장

### 문제

Outbox Worker가 outbox 테이블에서 PENDING 이벤트를 조회하여 Kafka에 발행한다.
이때 발행 직후 바로 상태를 SENT로 변경하면, 실제 Kafka Broker에 도달하지 못한 이벤트가 유실된다.

```
[위험한 흐름]
1. outbox 테이블에서 PENDING 이벤트 조회
2. kafkaTemplate.send() 호출
3. 상태를 SENT로 변경 ← Kafka Broker에 도달했는지 모르는 상태에서 변경
4. 실제로는 네트워크 오류로 Kafka에 도달 실패
5. 이벤트 유실 (outbox에서 더 이상 조회되지 않음)
```

### 해결: Kafka Producer 비동기 ACK 콜백

`KafkaTemplate.send()`는 `CompletableFuture<SendResult>`를 반환한다.
Kafka Broker가 메시지를 수신하고 ACK를 보내야만 성공으로 처리한다.

```java
@Component
@RequiredArgsConstructor
public class OutboxWorker {

    private final OutboxRepository outboxRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;

    @Scheduled(fixedDelay = 1000)
    public void publishPendingEvents() {
        List<OutboxEvent> events = outboxRepository.findByStatus(OutboxStatus.PENDING);

        for (OutboxEvent event : events) {
            kafkaTemplate.send(event.getTopic(), event.getKey(), event.getPayload())
                .whenComplete((result, ex) -> {
                    if (ex == null) {
                        // Kafka Broker ACK 수신 성공 → SENT로 변경
                        event.markAsSent();
                        outboxRepository.save(event);
                    } else {
                        // Kafka 발행 실패 → PENDING 유지 (다음 polling에서 재시도)
                        log.error("Kafka publish failed - eventId: {}, topic: {}",
                            event.getEventId(), event.getTopic(), ex);
                        event.incrementRetryCount();
                        outboxRepository.save(event);
                    }
                });
        }
    }
}
```

### 동작 흐름

```
1. outbox 테이블에서 PENDING 이벤트 조회
2. kafkaTemplate.send() 호출 (비동기)
3. Kafka Broker가 메시지 수신 후 ACK 반환
4. ACK 성공 콜백 → outbox 상태를 SENT로 변경
5. ACK 실패 콜백 → PENDING 유지 + retry_count 증가 → 다음 polling에서 재시도
```

### 핵심 원칙

- **ACK를 받기 전에는 절대 상태를 변경하지 않는다.**
- 네트워크 오류, Broker 장애 등으로 ACK가 오지 않으면 PENDING 상태가 유지되어 자동 재시도된다.
- 이로 인해 동일 이벤트가 중복 발행될 수 있지만, Consumer 측 Inbox 패턴이 이를 방어한다.

---

## 2. @RetryableTopic — 자동 재시도

### 개념

Spring Kafka의 `@RetryableTopic`을 사용하여
Consumer 처리 실패 시 별도의 retry topic으로 메시지를 이동시켜 자동 재시도한다.

### 동작 흐름

```
routine.execution.done (원본 토픽)
  → 처리 실패
  → routine.execution.done-retry-0 (1차 재시도, 1초 후)
  → routine.execution.done-retry-1 (2차 재시도, 2초 후)
  → routine.execution.done-retry-2 (3차 재시도, 4초 후)
  → routine.execution.done-dlt (DLT, 최종 실패)
```

### 적용 예시

```java
@RetryableTopic(
    attempts = "4",
    backoff = @Backoff(delay = 1000, multiplier = 2),
    dltStrategy = DltStrategy.ALWAYS_RETRY_ON_ERROR
)
@KafkaListener(topics = "routine.execution.done")
public void handleRoutineCompleted(RoutineCompletedEvent event) {
    // 처리 로직
}
```

### 선택 이유

- Spring Kafka 네이티브 지원 — 별도 인프라 불필요
- Exponential Backoff — 일시적 장애(네트워크, DB 타임아웃)에 효과적
- 토픽 기반 격리 — retry 메시지가 원본 토픽의 처리를 블로킹하지 않음

---

## 3. Inbox 패턴 — 중복 처리 방지

### 개념

Consumer가 이벤트를 처리하기 전에 `inbox` 테이블에 event_id를 기록한다.
동일한 event_id가 이미 존재하면 중복으로 판단하고 처리를 스킵한다.

### 테이블 구조

```sql
CREATE TABLE inbox (
    event_id    UUID PRIMARY KEY,
    topic       VARCHAR(255) NOT NULL,
    processed_at TIMESTAMP NOT NULL DEFAULT now()
);
```

### 처리 흐름

```
1. 이벤트 수신
2. inbox 테이블에서 event_id 조회
3. 이미 존재 → 스킵 (중복)
4. 존재하지 않음 → 비즈니스 로직 처리 + inbox에 event_id 저장 (같은 트랜잭션)
```

### 적용 예시

```java
@Transactional
public void process(DomainEvent event) {
    if (inboxRepository.existsById(event.getEventId())) {
        log.info("Duplicate event skipped: {}", event.getEventId());
        return;
    }

    // 비즈니스 로직 처리
    doProcess(event);

    // inbox에 기록
    inboxRepository.save(new InboxEntry(event.getEventId(), event.getTopic()));
}
```

### 선택 이유

- Kafka의 At-Least-Once 특성상 중복 수신이 불가피
- Outbox 패턴의 retry로 인한 중복 발행 가능성 존재
- DB 레벨의 Unique Constraint로 확실한 중복 차단

### Outbox ↔ Inbox 대칭 구조

```
[Producer Service]                    [Consumer Service]

 비즈니스 로직                          이벤트 수신
     ↓                                     ↓
 도메인 데이터 저장                      inbox 중복 확인
     +                                     ↓
 outbox 테이블 저장                     비즈니스 로직 처리
     ↓                                     +
 Outbox Worker → Kafka →              inbox 테이블 저장
```

Producer는 Outbox로 "반드시 발행"을 보장하고,
Consumer는 Inbox로 "정확히 한 번 처리"를 보장한다.

---

## 4. DLT (Dead Letter Topic) — 최종 실패 처리

### 개념

`@RetryableTopic`의 모든 재시도가 실패한 메시지는
DLT(Dead Letter Topic)로 이동한다.

### 처리 전략

```java
@DltHandler
public void handleDlt(RoutineCompletedEvent event, @Header(KafkaHeaders.ORIGINAL_TOPIC) String topic) {
    log.error("DLT received - topic: {}, event: {}", topic, event);
    // 1. 알림 발송 (Slack, 이메일 등)
    // 2. 실패 이벤트 DB 저장 (수동 재처리용)
}
```

### DLT 메시지 처리 방침

| 처리 방식 | 설명 |
|---|---|
| 알림 | 운영자에게 Slack/이메일 알림 발송 |
| 기록 | 실패 이벤트를 DB에 저장하여 추적 |
| 수동 재처리 | 원인 분석 후 관리자가 수동으로 재처리 |

### 선택 이유

- 무한 재시도는 시스템 리소스를 낭비하고 장애를 확산시킬 수 있음
- 재시도 횟수를 제한하되, 실패 메시지를 유실하지 않고 격리
- 운영자가 원인을 파악하고 조치할 수 있는 구조 확보

---

## 전체 흐름 요약

```
[Producer Service]
  비즈니스 로직 + outbox 테이블 저장 (같은 트랜잭션)
       ↓
  Outbox Worker → Kafka publish (비동기)
       ↓
  Kafka Broker ACK 수신
       ↓ 성공                    ↓ 실패
  outbox 상태 SENT           outbox PENDING 유지 → 재시도
       ↓
[Kafka]
       ↓
[Consumer Service]
  1. 이벤트 수신
  2. Inbox 중복 확인 → 중복이면 스킵
  3. 비즈니스 로직 처리 + inbox 저장 (같은 트랜잭션)
  4. 처리 실패 시 → @RetryableTopic (자동 재시도, backoff)
  5. 재시도 모두 실패 → DLT (알림 + 수동 처리)
```

---

## Consequences

### Positive

- Producer-Consumer 양쪽 모두 이벤트 정합성 보장 (Outbox + Inbox)
- 일시적 장애에 대한 자동 복구 (@RetryableTopic)
- 중복 처리 방지로 데이터 정합성 확보 (Inbox)
- 최종 실패 메시지 유실 방지 (DLT)

### Negative

- Consumer 서비스마다 inbox 테이블 관리 필요
- DLT 모니터링 및 수동 재처리 운영 프로세스 필요
- 이벤트에 반드시 고유한 event_id가 포함되어야 함

---

## Related ADRs

- [ADR-0012: Outbox 패턴 도입](adr-0012-outbox-pattern.md) — Producer 측 이벤트 발행 정합성
- [ADR-0013: 멱등성 전략](adr-0013-idempotency-strategy.md) — 시스템 전반 멱등성 원칙
