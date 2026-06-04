package com.routinely.challenge_service.infrastructure.kafka;

import com.routinely.challenge_service.domain.outbox.ChallengeOutbox;
import com.routinely.challenge_service.domain.outbox.ChallengeOutboxRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class ChallengeOutboxPoller {

    private static final int BATCH_SIZE = 100;
    private static final int MAX_RETRY = 5;

    private final ChallengeOutboxRepository outboxRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final Clock clock;

    @Scheduled(fixedDelay = 1000)
    @Transactional
    public void publish() {
        List<ChallengeOutbox> pendingForUpdate = outboxRepository.findPendingForUpdate(BATCH_SIZE);
        for (ChallengeOutbox outbox : pendingForUpdate) {
            try {
                SendResult<String, String> result = kafkaTemplate
                        .send(outbox.getEventType(), outbox.getAggregateId().toString(), outbox.getPayload())
                        .get();
                outbox.markPublished(LocalDateTime.now(clock));
                log.info("Outbox published - id: {}, topic: {}, partition: {}, offset: {}",
                        outbox.getId(),
                        result.getRecordMetadata().topic(),
                        result.getRecordMetadata().partition(),
                        result.getRecordMetadata().offset());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                markPublishFailed(outbox, e);
            } catch (Exception e) {
                markPublishFailed(outbox, e);
            }
        }
    }

    private void markPublishFailed(ChallengeOutbox outbox, Exception e) {
        outbox.incrementRetry();
        if (outbox.getRetryCount() > MAX_RETRY) {
            outbox.markFailed();
        }
        log.error("Outbox publish failed - id: {}, eventType: {}, retryCount: {}",
                outbox.getId(), outbox.getEventType(), outbox.getRetryCount(), e);
    }
}
