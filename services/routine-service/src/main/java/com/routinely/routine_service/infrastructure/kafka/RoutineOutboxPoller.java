package com.routinely.routine_service.infrastructure.kafka;

import com.routinely.routine_service.domain.outbox.RoutineOutbox;
import com.routinely.routine_service.domain.outbox.RoutineOutboxRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * routine_outbox의 PENDING 행을 Kafka로 발행한다. (#61, ADR-0012)
 *
 * <p><b>첫 실패에서 배치를 멈춘다.</b> 실패한 행을 건너뛰고 뒤 행을 계속 보내면 같은 파티션 키(userId)의
 * 이벤트 순서가 뒤집히고, 브로커 장애 시에는 행마다 대기 시간이 쌓여 잠금을 오래 잡는다. 멈춘 행은 다음
 * 폴링에서 맨 앞부터 다시 시도하고, 재시도 한도를 넘기면 FAILED가 되어 뒤 행의 발행을 더 막지 않는다.
 *
 * <p>{@code send()} 자체의 메타데이터 대기는 {@code max.block.ms}로, ACK 대기는 {@link #ACK_TIMEOUT_SECONDS}로
 * 제한한다(application.yaml). 전달 보장은 at-least-once다 — ACK 타임아웃 뒤 늦게 도착한 메시지나 ACK 후
 * 커밋 실패는 재전송을 만들고, 소비자가 eventId로 걸러 낸다.
 */
@Component
@Slf4j
public class RoutineOutboxPoller {

    private static final int BATCH_SIZE = 100;
    private static final int MAX_RETRY = 5;
    private static final long ACK_TIMEOUT_SECONDS = 3;

    private final RoutineOutboxRepository outboxRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final Clock clock;
    private final Counter failedCounter;

    public RoutineOutboxPoller(RoutineOutboxRepository outboxRepository,
                               KafkaTemplate<String, String> kafkaTemplate,
                               Clock clock,
                               MeterRegistry meterRegistry) {
        this.outboxRepository = outboxRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.clock = clock;
        this.failedCounter = Counter.builder("routinely.outbox.failed")
                .description("재시도 한도를 넘겨 FAILED로 전환된 Outbox 행 수 — 0보다 크면 수동 확인이 필요하다")
                .tag("service", "routine-service")
                .register(meterRegistry);
    }

    @Scheduled(fixedDelay = 1000)
    @Transactional
    public void publish() {
        List<RoutineOutbox> pendingForUpdate = outboxRepository.findPendingForUpdate(BATCH_SIZE);
        for (RoutineOutbox outbox : pendingForUpdate) {
            try {
                SendResult<String, String> result = kafkaTemplate
                        .send(outbox.getEventType(), outbox.getPartitionKey(), outbox.getPayload())
                        .get(ACK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                outbox.markPublished(LocalDateTime.now(clock));
                log.info("Outbox published - id: {}, topic: {}, partition: {}, offset: {}",
                        outbox.getId(),
                        result.getRecordMetadata().topic(),
                        result.getRecordMetadata().partition(),
                        result.getRecordMetadata().offset());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                markPublishFailed(outbox, e);
                break;
            } catch (Exception e) {
                markPublishFailed(outbox, e);
                break;
            }
        }
    }

    private void markPublishFailed(RoutineOutbox outbox, Exception e) {
        outbox.incrementRetry();
        if (outbox.getRetryCount() > MAX_RETRY) {
            outbox.markFailed();
            failedCounter.increment();
            log.error("Outbox FAILED — 재시도 한도 초과, 수동 확인 필요 - id: {}, eventType: {}, idempotencyKey: {}",
                    outbox.getId(), outbox.getEventType(), outbox.getIdempotencyKey(), e);
            return;
        }
        log.warn("Outbox publish failed — 다음 폴링에서 재시도 - id: {}, eventType: {}, retryCount: {}",
                outbox.getId(), outbox.getEventType(), outbox.getRetryCount(), e);
    }
}
