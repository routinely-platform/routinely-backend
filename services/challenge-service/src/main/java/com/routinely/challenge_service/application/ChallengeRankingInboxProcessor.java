package com.routinely.challenge_service.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.routinely.challenge_service.application.event.ChallengeMemberJoinedEvent;
import com.routinely.challenge_service.application.routine.RoutineExecutionCompletedPayload;
import com.routinely.challenge_service.domain.inbox.ChallengeInbox;
import com.routinely.challenge_service.domain.inbox.ChallengeInboxRepository;
import com.routinely.challenge_service.domain.inbox.InboxStatus;
import com.routinely.challenge_service.domain.member.ChallengeMemberRepository;
import com.routinely.challenge_service.domain.member.MembershipStatus;
import com.routinely.challenge_service.domain.summary.ChallengeMemberSummary;
import com.routinely.challenge_service.domain.summary.ChallengeMemberSummaryRepository;
import com.routinely.challenge_service.infrastructure.kafka.KafkaTopic;
import com.routinely.challenge_service.infrastructure.redis.ChallengeRankingRedisRepository;
import com.routinely.core.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
    private final ChallengeMemberRepository memberRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public ChallengeRankingInboxProcessor(ChallengeInboxRepository inboxRepository,
                                          ChallengeMemberSummaryRepository summaryRepository,
                                          ChallengeRankingRedisRepository rankingRedisRepository,
                                          ChallengeMemberRepository memberRepository,
                                          ObjectMapper objectMapper,
                                          Clock clock) {
        this.inboxRepository = inboxRepository;
        this.summaryRepository = summaryRepository;
        this.rankingRedisRepository = rankingRedisRepository;
        this.memberRepository = memberRepository;
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
            case KafkaTopic.ROUTINE_EXECUTION_COMPLETED, KafkaTopic.ROUTINE_EXECUTION_CANCELLED ->
                    processExecutionSnapshot(inbox.getEventType(), inbox.getPayload());
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
     * 멤버 참여 — 0회 랭킹 행을 생성한다. 완료 이벤트가 먼저 처리돼 이미 집계 행이 있으면
     * 점수를 0으로 되돌리지 않도록 건드리지 않는다.
     */
    private void processMemberJoined(String payloadJson) {
        ChallengeMemberJoinedEvent event = deserialize(payloadJson, ChallengeMemberJoinedEvent.class,
                KafkaTopic.CHALLENGE_MEMBER_JOINED);

        var member = memberRepository.findLockedByChallengeIdAndUserId(event.challengeId(), event.userId())
                .orElseThrow(() -> new BusinessException(INTERNAL_SERVER_ERROR, "랭킹 멤버를 찾을 수 없습니다."));
        if (summaryRepository.findByChallengeIdAndUserId(event.challengeId(), event.userId()).isPresent()) {
            return;
        }

        ChallengeMemberSummary summary = ChallengeMemberSummary.create(event.challengeId(), event.userId());
        summaryRepository.save(summary);
        if (member.getStatus() == MembershipStatus.ACTIVE) {
            rankingRedisRepository.updateScore(event.challengeId(), event.userId(), 0);
        }
    }

    /**
     * 루틴 완료·취소 — routine-service가 계산한 인정 횟수 스냅샷으로 summary를 UPSERT하고 ZSET 점수를 덮어쓴다.
     * 두 이벤트 모두 변경 후 절대값이라 처리 코드가 같다. 스냅샷이 도달 시각까지 담고 있어 이전 이벤트를
     * 몰라도 되므로, 역순·중간 누락이 있어도 가장 큰 revision 하나로 최종 상태가 정해진다.
     */
    private void processExecutionSnapshot(String eventType, String payloadJson) {
        RoutineExecutionCompletedPayload payload = deserialize(payloadJson,
                RoutineExecutionCompletedPayload.class, eventType);
        if (payload.challengeId() == null) return;
        validate(eventType, payload);
        // 이미 존재하는 멤버 행을 잠가 summary 최초 INSERT와 revision 비교도 직렬화한다.
        var member = memberRepository.findLockedByChallengeIdAndUserId(payload.challengeId(), payload.userId())
                .orElseThrow(() -> new BusinessException(INTERNAL_SERVER_ERROR, "랭킹 멤버를 찾을 수 없습니다."));
        ChallengeMemberSummary summary = summaryRepository
                .findByChallengeIdAndUserId(payload.challengeId(), payload.userId())
                .orElseGet(() -> ChallengeMemberSummary.create(payload.challengeId(), payload.userId()));

        if (payload.revision() <= summary.getRevision()) return;
        summary.applyAcceptedCount(payload.acceptedCount(), payload.revision(), toLocal(payload.reachedAt()));
        summaryRepository.save(summary);

        if (member.getStatus() == MembershipStatus.ACTIVE) {
            rankingRedisRepository.updateScore(payload.challengeId(), payload.userId(), payload.acceptedCount());
        }
    }

    private void validate(String eventType, RoutineExecutionCompletedPayload payload) {
        if (payload.challengeId() == null || payload.userId() == null
                || payload.acceptedCount() == null || payload.acceptedCount() < 0
                || payload.revision() == null || payload.revision() <= 0) {
            throw new BusinessException(INTERNAL_SERVER_ERROR,
                    "%s 필수 집계 필드가 누락되었습니다. eventId=%s".formatted(eventType, payload.eventId()));
        }
        // 도달 시각은 인정 횟수와 짝이다 — 1회 이상이면 반드시 있고, 0회면 없어야 한다.
        if ((payload.acceptedCount() > 0) != (payload.reachedAt() != null)) {
            throw new BusinessException(INTERNAL_SERVER_ERROR,
                    "%s acceptedCount와 reachedAt이 짝을 이루지 않습니다. eventId=%s".formatted(eventType, payload.eventId()));
        }
    }

    /** UTC ISO-8601 도달 시각을 서비스 시간대 벽시계로 바꾼다. null(0회)은 그대로 둔다. */
    private LocalDateTime toLocal(String reachedAt) {
        return reachedAt == null ? null : Instant.parse(reachedAt).atZone(clock.getZone()).toLocalDateTime();
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
