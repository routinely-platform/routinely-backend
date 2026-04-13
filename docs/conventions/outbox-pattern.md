# Outbox 패턴 구현

> Kafka publish는 반드시 Outbox 테이블을 경유한다. `kafkaTemplate.send()` 직접 호출 금지.

---

## Outbox Entity

```java
@Entity
@Table(name = "challenge_outbox")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChallengeOutbox extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String topic;

    @Column(nullable = false)
    private String partitionKey;   // Kafka 파티션 키

    @Column(columnDefinition = "TEXT", nullable = false)
    private String payload;        // JSON 직렬화된 이벤트

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OutboxStatus status = OutboxStatus.PENDING;

    public static ChallengeOutbox of(String topic, String partitionKey, String payload) {
        ChallengeOutbox outbox = new ChallengeOutbox();
        outbox.topic        = topic;
        outbox.partitionKey = partitionKey;
        outbox.payload      = payload;
        return outbox;
    }

    public void markPublished() {
        this.status = OutboxStatus.PUBLISHED;
    }
}
```

---

## 도메인 저장 + Outbox INSERT (같은 트랜잭션)

```java
@Transactional
public void join(Long challengeId, Long userId) {
    Challenge challenge = findChallengeByIdOrThrow(challengeId);
    ChallengeMembers member = challenge.addMember(userId);
    challengeMemberRepository.save(member);

    // 같은 트랜잭션 내 Outbox INSERT — DB 커밋과 동시에 보장
    ChallengeMemberJoinedEvent event = new ChallengeMemberJoinedEvent(challengeId, userId);
    String payload = objectMapper.writeValueAsString(event);
    outboxRepository.save(
        ChallengeOutbox.of(KafkaTopics.CHALLENGE_MEMBER_JOINED, String.valueOf(challengeId), payload)
    );
}
```

---

## Outbox Worker

```java
@Component
@RequiredArgsConstructor
@Slf4j
public class ChallengeOutboxWorker {

    private final ChallengeOutboxRepository outboxRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;

    @Scheduled(fixedDelay = 1000)   // 1초 polling
    @Transactional
    public void publish() {
        List<ChallengeOutbox> pending = outboxRepository
                .findTop100ByStatusOrderByCreatedAtAsc(OutboxStatus.PENDING);

        for (ChallengeOutbox outbox : pending) {
            try {
                kafkaTemplate.send(outbox.getTopic(), outbox.getPartitionKey(), outbox.getPayload());
                outbox.markPublished();
            } catch (Exception e) {
                log.error("Outbox publish failed - id: {}, topic: {}", outbox.getId(), outbox.getTopic(), e);
            }
        }
    }
}
```
