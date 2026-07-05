package com.routinely.challenge_service.infrastructure.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.routinely.challenge_service.application.event.ChallengeMemberJoinedEvent;
import com.routinely.challenge_service.application.routine.RoutineExecutionCompletedPayload;
import com.routinely.challenge_service.domain.inbox.ChallengeInbox;
import com.routinely.challenge_service.domain.inbox.ChallengeInboxRepository;
import com.routinely.core.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import static com.routinely.core.exception.ErrorCode.INTERNAL_SERVER_ERROR;

/**
 * 랭킹 집계용 이벤트 Consumer. (#48, ADR-0028)
 *
 * <p>두 토픽을 소비한다.
 * <ul>
 *     <li>{@code challenge.member.joined} — 멤버 참여 시 0% 랭킹 행을 만들기 위함</li>
 *     <li>{@code routine.execution.completed} — 달성률 갱신을 위함 (routine-service #61 발행)</li>
 * </ul>
 *
 * <p>Inbox 패턴(ADR-0013/0014)에 따라 수신 메시지를 challenge_inbox에 RECEIVED 상태로 저장만 하고 즉시 ACK한다.
 * 달성률 재계산·summary UPSERT·ZSET 갱신 등 후속 처리는 {@code ChallengeInboxScheduler}가 수행하므로,
 * Kafka 소비가 처리 지연·실패와 결합되지 않는다.
 *
 * <p>멱등성은 두 단계로 보장한다.
 * <ol>
 *     <li>일반적인 중복 수신은 {@code existsByMessageId} 사전 조회로 거른다.</li>
 *     <li>동시 수신 race로 저장 시점에 UNIQUE(message_id)가 위반되면
 *         {@link DataIntegrityViolationException}을 잡아 무시 후 ACK한다.</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChallengeRankingInboxConsumer {

    private static final String AGGREGATE_TYPE_CHALLENGE = "CHALLENGE";

    private final ChallengeInboxRepository inboxRepository;
    private final ObjectMapper objectMapper;

    @KafkaListener(
            topics = KafkaTopic.CHALLENGE_MEMBER_JOINED,
            groupId = "challenge-service.ranking.member.joined"
    )
    public void consumeMemberJoined(String message) {
        ChallengeMemberJoinedEvent payload = deserialize(message, ChallengeMemberJoinedEvent.class,
                KafkaTopic.CHALLENGE_MEMBER_JOINED);
        store(payload.eventId(), KafkaTopic.CHALLENGE_MEMBER_JOINED, message, payload.challengeId());
    }

    @KafkaListener(
            topics = KafkaTopic.ROUTINE_EXECUTION_COMPLETED,
            groupId = "challenge-service.ranking.routine.execution.completed"
    )
    public void consumeRoutineExecutionCompleted(String message) {
        RoutineExecutionCompletedPayload payload = deserialize(message,
                RoutineExecutionCompletedPayload.class, KafkaTopic.ROUTINE_EXECUTION_COMPLETED);
        store(payload.eventId(), KafkaTopic.ROUTINE_EXECUTION_COMPLETED, message, payload.challengeId());
    }

    private void store(String messageId, String eventType, String message, Long challengeId) {
        // 1차 멱등성: 일반적인 중복 수신은 사전 조회로 거른다.
        if (inboxRepository.existsByMessageId(messageId)) {
            log.info("[Inbox] 중복 수신 무시 - messageId: {}, eventType: {}, challengeId: {}",
                    messageId, eventType, challengeId);
            return;
        }

        // 2차 멱등성: 동시 수신 race로 저장 시점에 UNIQUE(message_id)가 위반되면 무시하고 ACK한다.
        try {
            inboxRepository.save(ChallengeInbox.received(
                    messageId, eventType, message, AGGREGATE_TYPE_CHALLENGE, challengeId));
            log.info("[Inbox] 수신 저장 - messageId: {}, eventType: {}, challengeId: {}",
                    messageId, eventType, challengeId);
        } catch (DataIntegrityViolationException e) {
            if (!inboxRepository.existsByMessageId(messageId)) {
                throw e;
            }
            log.info("[Inbox] 동시 수신으로 인한 중복 저장 무시 - messageId: {}, eventType: {}",
                    messageId, eventType);
        }
    }

    private <T> T deserialize(String message, Class<T> type, String eventType) {
        try {
            return objectMapper.readValue(message, type);
        } catch (JsonProcessingException e) {
            throw new BusinessException(INTERNAL_SERVER_ERROR,
                    "%s 이벤트 역직렬화에 실패했습니다.".formatted(eventType));
        }
    }
}
