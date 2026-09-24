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

실제 구현은 `ChallengeOutboxPoller` · `RoutineOutboxPoller`이며 두 폴러는 같은 모양이다.

```java
@Scheduled(fixedDelay = 1000)   // 1초 polling
@Transactional
public void publish() {
    // FOR UPDATE SKIP LOCKED — 여러 인스턴스가 같은 행을 동시에 잡지 않는다
    List<ChallengeOutbox> pending = outboxRepository.findPendingForUpdate(100);

    for (ChallengeOutbox outbox : pending) {
        try {
            kafkaTemplate.send(outbox.getEventType(), key, outbox.getPayload())
                    .get(3, TimeUnit.SECONDS);          // 브로커 ACK를 받은 뒤에만
            outbox.markPublished(LocalDateTime.now(clock));
        } catch (Exception e) {
            markPublishFailed(outbox, e);                // retry_count++, 한도 초과 시 FAILED
            break;                                       // 첫 실패에서 멈춘다 — 순서 보존·잠금 시간 제한
        }
    }
}
```

- `send()`만 호출하고 결과를 기다리지 않으면 전송 실패를 모른 채 PUBLISHED가 된다. ACK를 기다린다.
- `send()` 자체의 대기는 `.get()` 타임아웃이 막지 못한다. `spring.kafka.producer.properties`의
  `max.block.ms`(3초) · `request.timeout.ms`(5초) · `delivery.timeout.ms`(10초)로 함께 제한한다.
- 실패 행을 건너뛰면 같은 파티션 키의 뒤 이벤트가 먼저 나간다(예: `member.joined` 실패 후 `member.left`
  먼저 도착). 첫 실패에서 멈추고, FAILED가 된 행만 뒤를 막지 않는다.
- FAILED 전환 시 `routinely.outbox.failed{service=...}` 카운터를 올린다. 0보다 크면 수동 확인 대상이다.

---

## routine-service 구현 (#61)

RoutineEventPublisher가 완료·취소·알림 일정 스냅샷을 JSON으로 저장한다.
쓰기 메서드는 Propagation.MANDATORY로 호출자의 트랜잭션 참여를 강제한다.
완료·취소·다중 완료와 루틴 시작·수정·중단에 연결한다. 챌린지 시작/탈퇴 경로는 #157/#158에서 연결한다.

- V6는 기존 테이블을 유지하며 (created_at, id) PENDING 부분 인덱스와
  partition_key, routine_event_revision_seq를 추가한다. 기존 행은 payload의 userId로 키를 복원한다.
- Outbox는 V1에 updated_at이 없는 이벤트 저장소라 challenge 선례처럼 BaseEntity를 상속하지 않는다.
- 폴러는 1초 간격으로 최대 100행을 FOR UPDATE SKIP LOCKED로 잠근다.
  Kafka 키는 aggregate ID가 아니라 partition_key(userId)다.
- send()가 반환한 future의 ACK를 최대 3초 기다린다. send() 자체의 메타데이터 대기는
  `spring.kafka.producer.properties.max.block.ms`(3초)로, 프로듀서 내부 재시도는
  `delivery.timeout.ms`(10초)로 제한한다. 기본값(60초·120초)이면 브로커 장애 시 폴링 한 번이
  트랜잭션과 행 잠금을 수 분간 잡는다.
- **첫 실패에서 배치를 멈춘다.** 실패 행을 건너뛰면 같은 파티션 키의 뒤 이벤트가 먼저 나가고, 장애 시
  행마다 대기가 누적된다. 멈춘 행은 다음 폴링에서 맨 앞부터 재시도한다.
- ACK 성공 뒤에만 PUBLISHED로 바꾸고, 실패 누적 6회에서 FAILED로 전환한다. FAILED가 되면 뒤 행을
  더 막지 않는다. 전환 시 `routinely.outbox.failed` 카운터를 올리고 ERROR 로그를 남긴다 — 이 값이
  0보다 크면 수동 확인 대상이다. 스레드 인터럽트는 복원하고 현재 배치 처리를 중단한다.
- 여러 인스턴스는 SKIP LOCKED로 서로 다른 행을 동시에 보내므로 **Kafka 도착 순서는 보장하지 않는다.**
  순서가 중요한 소비자는 payload의 revision으로 판정한다.
- created_at은 발행 창구가 Clock으로 넣는다(폴링 정렬 키 — 테스트에서 고정 가능).
- ACK 후 DB 커밋 실패나 ACK 타임아웃은 재전송을 만들 수 있다. 전달 보장은 at-least-once이며,
  Outbox UNIQUE 키는 중복 INSERT를 막고 소비자는 payload의 동일한 eventId로 중복 처리를 막아야 한다.
- 챌린지 실행 이벤트만 시퀀스 revision과 acceptedCount를 포함한다. 개인 루틴은 둘 다 생략한다.
  개인 취소 키는 revision 대신 eventId를 붙인다. 알림 스냅샷 키에도 eventId를 붙인다.
- 알림 스냅샷은 개인·챌린지 구분 없이 revision을 싣는다(소비자의 옛 스냅샷 폐기 기준).
- 알림 스냅샷은 중단 또는 preferredTime 해제도 저장한다. 요일 마스크는 `Weekdays.toCodes`로
  MON~SUN 배열로 변환하고, 미설정은 빈 배열이 아니라 null로 싣는다.
