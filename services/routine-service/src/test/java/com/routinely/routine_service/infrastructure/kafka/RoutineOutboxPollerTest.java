package com.routinely.routine_service.infrastructure.kafka;

import com.routinely.routine_service.domain.outbox.RoutineOutbox;
import com.routinely.routine_service.domain.outbox.RoutineOutboxRepository;
import com.routinely.routine_service.domain.outbox.OutboxStatus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("루틴 Outbox 폴러")
class RoutineOutboxPollerTest {

    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-05-24T00:00:00Z"), ZoneId.of("Asia/Seoul"));

    private RoutineOutboxRepository outboxRepository;
    private KafkaTemplate<String, String> kafkaTemplate;
    private RoutineOutboxPoller poller;
    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        outboxRepository = mock(RoutineOutboxRepository.class);
        kafkaTemplate = mock(KafkaTemplate.class);
        meterRegistry = new SimpleMeterRegistry();
        poller = new RoutineOutboxPoller(outboxRepository, kafkaTemplate, FIXED_CLOCK, meterRegistry);
    }

    @Test
    @DisplayName("ACK_성공시_Kafka에_발행하고_PUBLISHED로_변경한다")
    void publish_whenAckSucceeds_marksPublished() {
        RoutineOutbox outbox = outbox();
        when(outboxRepository.findPendingForUpdate(100)).thenReturn(List.of(outbox));
        when(kafkaTemplate.send("routine.execution.completed", "1", "{\"challengeId\":1}"))
                .thenReturn(CompletableFuture.completedFuture(sendResult()));

        poller.publish();

        verify(outboxRepository).findPendingForUpdate(100);
        verify(kafkaTemplate).send("routine.execution.completed", "1", "{\"challengeId\":1}");
        assertThat(outbox.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
        assertThat(outbox.getPublishedAt()).isEqualTo(LocalDateTime.now(FIXED_CLOCK));
        assertThat(outbox.getRetryCount()).isZero();
    }

    @Test
    @DisplayName("ACK_실패시_retryCount를_증가시키고_PENDING을_유지한다")
    void publish_whenAckFails_incrementsRetryCount() {
        RoutineOutbox outbox = outbox();
        when(outboxRepository.findPendingForUpdate(100)).thenReturn(List.of(outbox));
        when(kafkaTemplate.send("routine.execution.completed", "1", "{\"challengeId\":1}"))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("send failed")));

        poller.publish();

        assertThat(outbox.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(outbox.getRetryCount()).isEqualTo(1);
        assertThat(outbox.getPublishedAt()).isNull();
    }

    @Test
    @DisplayName("ACK_실패후_최대재시도_초과시_FAILED로_변경한다")
    void publish_whenRetryCountExceedsMaxRetry_marksFailed() {
        RoutineOutbox outbox = outbox();
        ReflectionTestUtils.setField(outbox, "retryCount", 5);
        when(outboxRepository.findPendingForUpdate(100)).thenReturn(List.of(outbox));
        when(kafkaTemplate.send("routine.execution.completed", "1", "{\"challengeId\":1}"))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("send failed")));

        poller.publish();

        assertThat(outbox.getStatus()).isEqualTo(OutboxStatus.FAILED);
        assertThat(outbox.getRetryCount()).isEqualTo(6);
        assertThat(outbox.getPublishedAt()).isNull();
        assertThat(meterRegistry.get("routinely.outbox.failed").counter().count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("앞 행이 실패하면 배치를 멈춰 뒤 행을 먼저 보내지 않는다")
    void publish_whenFirstFails_stopsBatchToKeepOrder() {
        RoutineOutbox first = outbox(10L, "{\"seq\":1}");
        RoutineOutbox second = outbox(11L, "{\"seq\":2}");
        when(outboxRepository.findPendingForUpdate(100)).thenReturn(List.of(first, second));
        when(kafkaTemplate.send("routine.execution.completed", "1", "{\"seq\":1}"))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("broker down")));

        poller.publish();

        verify(kafkaTemplate, never()).send("routine.execution.completed", "1", "{\"seq\":2}");
        assertThat(first.getRetryCount()).isEqualTo(1);
        assertThat(second.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(second.getRetryCount()).isZero();
    }

    @Test
    @DisplayName("send() 자체가 예외를 던져도(메타데이터 대기 초과) 배치를 멈춘다")
    void publish_whenSendThrows_stopsBatch() {
        RoutineOutbox first = outbox(10L, "{\"seq\":1}");
        RoutineOutbox second = outbox(11L, "{\"seq\":2}");
        when(outboxRepository.findPendingForUpdate(100)).thenReturn(List.of(first, second));
        when(kafkaTemplate.send("routine.execution.completed", "1", "{\"seq\":1}"))
                .thenThrow(new org.apache.kafka.common.errors.TimeoutException("max.block.ms exceeded"));

        poller.publish();

        verify(kafkaTemplate, never()).send("routine.execution.completed", "1", "{\"seq\":2}");
        assertThat(first.getRetryCount()).isEqualTo(1);
        assertThat(second.getRetryCount()).isZero();
    }

    @Test
    @DisplayName("ACK 대기 시간이 초과되면 발행 완료로 처리하지 않는다")
    @SuppressWarnings("unchecked")
    void publish_timeout_keepsPending() throws Exception {
        RoutineOutbox outbox = outbox();
        var future = (CompletableFuture<SendResult<String, String>>) mock(CompletableFuture.class);
        when(outboxRepository.findPendingForUpdate(100)).thenReturn(List.of(outbox));
        when(kafkaTemplate.send("routine.execution.completed", "1", "{\"challengeId\":1}")).thenReturn(future);
        when(future.get(3, TimeUnit.SECONDS)).thenThrow(new TimeoutException());
        poller.publish();
        assertThat(outbox.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(outbox.getRetryCount()).isEqualTo(1);
        assertThat(outbox.getPublishedAt()).isNull();
    }

    private RoutineOutbox outbox() {
        return outbox(10L, "{\"challengeId\":1}");
    }

    private RoutineOutbox outbox(Long id, String payload) {
        RoutineOutbox outbox = RoutineOutbox.create(
                "ROUTINE_EXECUTION",
                id,
                "routine.execution.completed",
                payload,
                "ROUTINE_EXECUTION:" + id + ":completed", "1",
                LocalDateTime.now(FIXED_CLOCK)
        );
        ReflectionTestUtils.setField(outbox, "id", id);
        return outbox;
    }

    private SendResult<String, String> sendResult() {
        ProducerRecord<String, String> producerRecord = new ProducerRecord<>(
                "routine.execution.completed",
                "1",
                "{\"challengeId\":1}"
        );
        RecordMetadata metadata = new RecordMetadata(
                new TopicPartition("routine.execution.completed", 0),
                0,
                42,
                0,
                1,
                17
        );
        return new SendResult<>(producerRecord, metadata);
    }
}
