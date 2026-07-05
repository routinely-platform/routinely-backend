package com.routinely.challenge_service.infrastructure.scheduler;

import com.routinely.challenge_service.application.ChallengeRankingInboxProcessor;
import com.routinely.challenge_service.domain.inbox.ChallengeInboxRepository;
import com.routinely.challenge_service.domain.inbox.InboxStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * challenge_inbox의 RECEIVED 건을 폴링해 랭킹 집계 갱신을 트리거하는 스케줄러. (#48, ADR-0014)
 *
 * <p>각 메시지는 {@link ChallengeRankingInboxProcessor}의 독립 트랜잭션으로 처리되며,
 * 한 건의 실패가 다른 건에 영향을 주지 않도록 try-catch로 격리한다.
 * 멀티 인스턴스 중복 실행은 {@link SchedulerLock} (Redis 기반)으로 방지한다. (ADR-0033)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChallengeInboxScheduler {

    private static final int BATCH_SIZE = 100;

    private final ChallengeInboxRepository inboxRepository;
    private final ChallengeRankingInboxProcessor inboxProcessor;

    @Scheduled(fixedDelay = 5000)
    @SchedulerLock(
            name = "challenge-inbox-processing",
            lockAtMostFor = "5m",
            lockAtLeastFor = "1s"
    )
    public void processReceivedInbox() {
        List<Long> targetIds = inboxRepository.findIdsByStatus(
                InboxStatus.RECEIVED, PageRequest.of(0, BATCH_SIZE));
        if (targetIds.isEmpty()) {
            return;
        }

        int processed = 0;
        int failed = 0;
        for (Long inboxId : targetIds) {
            try {
                inboxProcessor.processInbox(inboxId);
                processed++;
            } catch (Exception e) {
                failed++;
                log.error("[Inbox] 처리 실패 - inboxId: {}", inboxId, e);
                safeRecordFailure(inboxId, e);
            }
        }
        log.info("[Inbox] 폴링 처리 완료 - 대상: {}건, 성공: {}건, 실패: {}건",
                targetIds.size(), processed, failed);
    }

    /**
     * 실패 상태 기록 자체가 실패해도 다음 메시지 처리가 중단되지 않도록 격리한다.
     * 여기서 발생한 예외는 운영 로그로만 남기고 삼킨다. (다음 폴링에서 재처리 가능)
     */
    private void safeRecordFailure(Long inboxId, Exception cause) {
        try {
            inboxProcessor.recordFailure(inboxId, cause.getMessage());
        } catch (Exception e) {
            log.error("[Inbox] 실패 상태 기록에 실패했습니다 - inboxId: {}", inboxId, e);
        }
    }
}
