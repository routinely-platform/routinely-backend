package com.routinely.routine_service.infrastructure.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.routinely.core.exception.BusinessException;
import com.routinely.routine_service.application.challenge.ChallengeCreatedPayload;
import com.routinely.routine_service.domain.inbox.RoutineInbox;
import com.routinely.routine_service.domain.inbox.RoutineInboxRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import static com.routinely.core.exception.ErrorCode.INTERNAL_SERVER_ERROR;

/**
 * {@code challenge.created} 이벤트 Consumer.
 *
 * <p>Inbox 패턴(B 방식)에 따라 수신 메시지를 routine_inbox에 RECEIVED 상태로 저장만 하고 즉시 ACK한다.
 * 루틴 템플릿 생성 등 후속 비즈니스 처리는 스케줄러가 수행하므로, Kafka 소비가 처리 지연·실패와 결합되지 않는다.
 *
 * <p>멱등성은 두 단계로 보장한다. (ADR-0013)
 * <ol>
 *     <li>일반적인 중복 수신은 {@code existsByMessageId} 사전 조회로 거른다.</li>
 *     <li>동시 수신 race로 저장 시점에 UNIQUE(message_id)가 위반되면
 *         {@link DataIntegrityViolationException} 을 잡아 무시 후 ACK한다.</li>
 * </ol>
 * 리스너에 트랜잭션을 두지 않으므로 {@code save()} 가 자체 트랜잭션으로 즉시 flush되어
 * 제약 위반 예외를 호출부에서 직접 잡을 수 있다(트랜잭션이 rollback-only로 마킹되어
 * 커밋 단계에서 예외가 터지는 문제를 피한다).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChallengeCreatedConsumer {

    private static final String AGGREGATE_TYPE_CHALLENGE = "CHALLENGE";

    private final RoutineInboxRepository inboxRepository;
    private final ObjectMapper objectMapper;

    @KafkaListener(
            topics = KafkaTopic.CHALLENGE_CREATED,
            groupId = "routine-service.challenge.created"
    )
    public void consume(String message) {
        ChallengeCreatedPayload payload = deserialize(message);

        // 1차 멱등성: 일반적인 중복 수신은 사전 조회로 거른다.
        if (inboxRepository.existsByMessageId(payload.eventId())) {
            log.info("[Inbox] 중복 수신 무시 - messageId: {}, challengeId: {}",
                    payload.eventId(), payload.challengeId());
            return;
        }

        // 2차 멱등성: 동시 수신 race로 저장 시점에 UNIQUE(message_id)가 위반되면
        // 이미 저장된 메시지이므로 무시하고 ACK한다.
        try {
            inboxRepository.save(RoutineInbox.received(
                    payload.eventId(),
                    KafkaTopic.CHALLENGE_CREATED,
                    message,
                    AGGREGATE_TYPE_CHALLENGE,
                    payload.challengeId()
            ));
            log.info("[Inbox] 수신 저장 - messageId: {}, challengeId: {}",
                    payload.eventId(), payload.challengeId());
        } catch (DataIntegrityViolationException e) {
            if (!isDuplicateMessage(payload.eventId())) {
                throw e;
            }
            log.info("[Inbox] 동시 수신으로 인한 중복 저장 무시 - messageId: {}, challengeId: {}",
                    payload.eventId(), payload.challengeId());
        }
    }

    private boolean isDuplicateMessage(String messageId) {
        return messageId != null && inboxRepository.existsByMessageId(messageId);
    }

    private ChallengeCreatedPayload deserialize(String message) {
        try {
            return objectMapper.readValue(message, ChallengeCreatedPayload.class);
        } catch (JsonProcessingException e) {
            throw new BusinessException(INTERNAL_SERVER_ERROR,
                    "challenge.created 이벤트 역직렬화에 실패했습니다.");
        }
    }
}
