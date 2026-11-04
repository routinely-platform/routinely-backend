package com.routinely.routine_service.infrastructure.kafka;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.routinely.core.constant.KafkaTopics;
import com.routinely.core.exception.BusinessException;
import com.routinely.routine_service.domain.inbox.RoutineInbox;
import com.routinely.routine_service.domain.inbox.RoutineInboxRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import static com.routinely.core.exception.ErrorCode.INTERNAL_SERVER_ERROR;

/**
 * {@code challenge.started} 이벤트 Consumer. (#157, event-spec.md §3)
 *
 * <p>Inbox 패턴(ADR-0013/0014)에 따라 수신 메시지를 routine_inbox에 RECEIVED 상태로 <b>원문 그대로</b> 저장하고
 * 즉시 ACK한다. 멤버별 루틴 생성은 {@code RoutineInboxScheduler} → {@code ChallengeStartedHandler}가 한다.
 *
 * <p><b>리스너는 페이로드 내용을 따지지 않는다.</b> 멱등성 키({@code eventId})와 집계 ID({@code challengeId})만
 * 꺼낸다. 필수 필드 누락 같은 문제는 핸들러가 처리 시점에 검증해 실패로 기록한다 — 그래야 원문과 {@code last_error}가
 * DB에 남아 보인다. 여기서 던지면 {@code DefaultErrorHandler}가 재시도 후 로그만 남기고 버린다.
 *
 * <p>⚠️ 예외는 하나 — <b>JSON으로 읽을 수 없거나 {@code eventId}가 없는 메시지</b>는 Inbox 행을 만들 수 없다.
 * 이때는 던질 수밖에 없고(ChallengeRankingInboxConsumer 선례), 재시도 후 오프셋이 넘어가 사실상 유실된다.
 * <b>로그가 유일한 흔적</b>이므로 {@code ERROR} 레벨로 받은 문자열 전문을 남긴다.
 *
 * <p>멱등성은 두 단계로 보장한다.
 * <ol>
 *     <li>일반적인 중복 수신은 {@code existsByMessageId} 사전 조회로 거른다.</li>
 *     <li>동시 수신 race로 저장 시점에 UNIQUE(message_id)가 위반되면
 *         {@link DataIntegrityViolationException}을 잡아 재조회 후 무시하고 ACK한다.</li>
 * </ol>
 * 리스너에 트랜잭션을 두지 않으므로 {@code save()}가 자체 트랜잭션으로 즉시 flush되어 제약 위반을 여기서 잡을 수 있다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChallengeStartedConsumer {

    private static final String AGGREGATE_TYPE_CHALLENGE = "CHALLENGE";

    private final RoutineInboxRepository inboxRepository;
    private final ObjectMapper objectMapper;

    @KafkaListener(
            topics = KafkaTopics.CHALLENGE_STARTED,
            groupId = "routine-service.challenge.started"
    )
    public void consume(String message) {
        Envelope envelope = readEnvelope(message);

        // 1차 멱등성: 일반적인 중복 수신은 사전 조회로 거른다.
        if (inboxRepository.existsByMessageId(envelope.eventId())) {
            log.info("[Inbox] 중복 수신 무시 - messageId: {}, eventType: {}, challengeId: {}",
                    envelope.eventId(), KafkaTopics.CHALLENGE_STARTED, envelope.challengeId());
            return;
        }

        // 2차 멱등성: 동시 수신 race로 UNIQUE(message_id)가 위반되면 이미 저장된 메시지이므로 무시하고 ACK한다.
        try {
            inboxRepository.save(RoutineInbox.received(envelope.eventId(), KafkaTopics.CHALLENGE_STARTED,
                    message, AGGREGATE_TYPE_CHALLENGE, envelope.challengeId()));
            log.info("[Inbox] 수신 저장 - messageId: {}, eventType: {}, challengeId: {}",
                    envelope.eventId(), KafkaTopics.CHALLENGE_STARTED, envelope.challengeId());
        } catch (DataIntegrityViolationException e) {
            if (!inboxRepository.existsByMessageId(envelope.eventId())) {
                throw e;
            }
            log.info("[Inbox] 동시 수신으로 인한 중복 저장 무시 - messageId: {}, eventType: {}",
                    envelope.eventId(), KafkaTopics.CHALLENGE_STARTED);
        }
    }

    /**
     * 멱등성 키와 집계 ID만 꺼낸다. 이 둘 없이는 Inbox 행을 만들 수 없어 여기서 실패시킨다.
     * 파싱이 안 되니 다른 필드를 뽑으려 하지 않고 <b>받은 문자열을 그대로</b> 로그에 남긴다 — 복구 근거는 이것뿐이다.
     */
    private Envelope readEnvelope(String message) {
        Envelope envelope;
        try {
            envelope = objectMapper.readValue(message, Envelope.class);
        } catch (JsonProcessingException e) {
            log.error("[Inbox] 역직렬화 실패로 저장할 수 없는 메시지 - eventType: {}, message: {}",
                    KafkaTopics.CHALLENGE_STARTED, message, e);
            throw new BusinessException(INTERNAL_SERVER_ERROR,
                    "%s 이벤트 역직렬화에 실패했습니다.".formatted(KafkaTopics.CHALLENGE_STARTED), e);
        }
        if (envelope == null || envelope.eventId() == null || envelope.eventId().isBlank()) {
            log.error("[Inbox] eventId가 없어 저장할 수 없는 메시지 - eventType: {}, message: {}",
                    KafkaTopics.CHALLENGE_STARTED, message);
            throw new BusinessException(INTERNAL_SERVER_ERROR,
                    "%s 이벤트에 eventId가 없습니다.".formatted(KafkaTopics.CHALLENGE_STARTED));
        }
        return envelope;
    }

    /** Inbox 저장에 필요한 두 값. 나머지 필드는 원문으로 저장하고 핸들러가 읽는다. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Envelope(String eventId, Long challengeId) {
    }
}
