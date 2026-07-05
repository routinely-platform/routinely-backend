package com.routinely.challenge_service.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.routinely.challenge_service.application.event.ChallengeMemberJoinedEvent;
import com.routinely.challenge_service.application.routine.RoutineExecutionCompletedPayload;
import com.routinely.challenge_service.domain.inbox.ChallengeInbox;
import com.routinely.challenge_service.domain.inbox.ChallengeInboxRepository;
import com.routinely.challenge_service.domain.inbox.InboxStatus;
import com.routinely.challenge_service.domain.summary.ChallengeMemberSummary;
import com.routinely.challenge_service.domain.summary.ChallengeMemberSummaryRepository;
import com.routinely.challenge_service.infrastructure.kafka.KafkaTopic;
import com.routinely.challenge_service.infrastructure.redis.ChallengeRankingRedisRepository;
import com.routinely.core.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;

import static com.routinely.core.exception.ErrorCode.INTERNAL_SERVER_ERROR;

/**
 * challenge_inbox의 RECEIVED 건을 event_type별로 처리해 랭킹 집계를 갱신한다. (#48, ADR-0028)
 *
 * <p>각 메시지는 독립 트랜잭션으로 처리된다. summary(DB) 갱신과 ZSET(Redis) 갱신을 한 메서드에서 수행하되
 * ZSET 갱신을 마지막에 두어, ZSET 실패 시 트랜잭션이 롤백되고 Inbox가 RECEIVED로 남아 재시도된다.
 * ZSET 갱신은 절대값(ZADD)이라 재시도로 같은 값을 덮어써도 멱등하다.
 */
@Slf4j
@Service
public class ChallengeRankingInboxProcessor {

    static final int MAX_RETRY = 5;
    private static final int MAX_ERROR_LENGTH = 1000;

    private final ChallengeInboxRepository inboxRepository;
    private final ChallengeMemberSummaryRepository summaryRepository;
    private final ChallengeRankingRedisRepository rankingRedisRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public ChallengeRankingInboxProcessor(ChallengeInboxRepository inboxRepository,
                                          ChallengeMemberSummaryRepository summaryRepository,
                                          ChallengeRankingRedisRepository rankingRedisRepository,
                                          ObjectMapper objectMapper,
                                          Clock clock) {
        this.inboxRepository = inboxRepository;
        this.summaryRepository = summaryRepository;
        this.rankingRedisRepository = rankingRedisRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional
    public void processInbox(Long inboxId) {
        ChallengeInbox inbox = inboxRepository.findById(inboxId)
                .orElseThrow(() -> new BusinessException(INTERNAL_SERVER_ERROR,
                        "처리할 Inbox 메시지를 찾을 수 없습니다. id=" + inboxId));

        // 다른 인스턴스/이전 폴링이 이미 처리한 경우 재처리하지 않는다.
        if (inbox.getStatus() != InboxStatus.RECEIVED) {
            return;
        }

        switch (inbox.getEventType()) {
            case KafkaTopic.CHALLENGE_MEMBER_JOINED -> processMemberJoined(inbox.getPayload());
            case KafkaTopic.ROUTINE_EXECUTION_COMPLETED -> processExecutionCompleted(inbox.getPayload());
            default -> log.warn("[Inbox] 처리 대상이 아닌 eventType - id: {}, eventType: {}",
                    inboxId, inbox.getEventType());
        }

        inbox.markProcessed(LocalDateTime.now(clock));
    }

    @Transactional
    public void recordFailure(Long inboxId, String errorMessage) {
        inboxRepository.findById(inboxId).ifPresent(inbox ->
                inbox.recordFailure(MAX_RETRY, truncate(errorMessage)));
    }

    /**
     * 멤버 참여 — 0% 랭킹 행을 생성한다. 완료 이벤트가 먼저 처리돼 이미 집계 행이 있으면
     * 점수를 0으로 되돌리지 않도록 건드리지 않는다.
     */
    private void processMemberJoined(String payloadJson) {
        ChallengeMemberJoinedEvent event = deserialize(payloadJson, ChallengeMemberJoinedEvent.class,
                KafkaTopic.CHALLENGE_MEMBER_JOINED);

        if (summaryRepository.findByChallengeIdAndUserId(event.challengeId(), event.userId()).isPresent()) {
            return;
        }

        ChallengeMemberSummary summary = ChallengeMemberSummary.create(event.challengeId(), event.userId());
        summaryRepository.save(summary);
        rankingRedisRepository.updateScore(event.challengeId(), event.userId(),
                summary.getAchievementRate().doubleValue());
    }

    /**
     * 루틴 완료 — routine-service가 계산한 집계값으로 summary를 UPSERT하고 ZSET 점수를 달성률로 갱신한다.
     */
    private void processExecutionCompleted(String payloadJson) {
        RoutineExecutionCompletedPayload payload = deserialize(payloadJson,
                RoutineExecutionCompletedPayload.class, KafkaTopic.ROUTINE_EXECUTION_COMPLETED);
        validate(payload);

        BigDecimal rate = payload.achievementRate();
        ChallengeMemberSummary summary = summaryRepository
                .findByChallengeIdAndUserId(payload.challengeId(), payload.userId())
                .orElseGet(() -> ChallengeMemberSummary.create(payload.challengeId(), payload.userId()));

        summary.applyCompletion(payload.totalScheduled(), payload.completedCount(), rate,
                parseOccurredAt(payload.occurredAt()));
        summaryRepository.save(summary);

        rankingRedisRepository.updateScore(payload.challengeId(), payload.userId(), rate.doubleValue());
    }

    private void validate(RoutineExecutionCompletedPayload payload) {
        if (payload.challengeId() == null || payload.userId() == null
                || payload.completedCount() == null || payload.totalScheduled() == null
                || payload.achievementRate() == null) {
            throw new BusinessException(INTERNAL_SERVER_ERROR,
                    "routine.execution.completed 필수 집계 필드가 누락되었습니다. eventId=" + payload.eventId());
        }
    }

    private LocalDateTime parseOccurredAt(String occurredAt) {
        return Instant.parse(occurredAt).atZone(clock.getZone()).toLocalDateTime();
    }

    private <T> T deserialize(String message, Class<T> type, String eventType) {
        try {
            return objectMapper.readValue(message, type);
        } catch (JsonProcessingException e) {
            throw new BusinessException(INTERNAL_SERVER_ERROR,
                    "%s 이벤트 역직렬화에 실패했습니다.".formatted(eventType));
        }
    }

    private String truncate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() <= MAX_ERROR_LENGTH ? message : message.substring(0, MAX_ERROR_LENGTH);
    }
}
