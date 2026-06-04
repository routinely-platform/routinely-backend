package com.routinely.challenge_service.infrastructure.kafka;

import com.routinely.challenge_service.domain.outbox.ChallengeOutbox;
import com.routinely.challenge_service.domain.outbox.ChallengeOutboxRepository;
import com.routinely.challenge_service.domain.outbox.OutboxStatus;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("ChallengeOutboxPoller")
class ChallengeOutboxPollerTest {

    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-05-24T00:00:00Z"), ZoneId.of("Asia/Seoul"));

    private ChallengeOutboxRepository outboxRepository;
    private KafkaTemplate<String, String> kafkaTemplate;
    private ChallengeOutboxPoller poller;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        outboxRepository = mock(ChallengeOutboxRepository.class);
        kafkaTemplate = mock(KafkaTemplate.class);
        poller = new ChallengeOutboxPoller(outboxRepository, kafkaTemplate, FIXED_CLOCK);
    }

    @Test
    @DisplayName("ACK_성공시_Kafka에_발행하고_PUBLISHED로_변경한다")
    void publish_whenAckSucceeds_marksPublished() {
        ChallengeOutbox outbox = outbox();
        when(outboxRepository.findPendingForUpdate(100)).thenReturn(List.of(outbox));
        when(kafkaTemplate.send("challenge.member.joined", "1", "{\"challengeId\":1}"))
                .thenReturn(CompletableFuture.completedFuture(sendResult()));

        poller.publish();

        verify(outboxRepository).findPendingForUpdate(100);
        verify(kafkaTemplate).send("challenge.member.joined", "1", "{\"challengeId\":1}");
        assertThat(outbox.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
        assertThat(outbox.getPublishedAt()).isEqualTo(LocalDateTime.now(FIXED_CLOCK));
        assertThat(outbox.getRetryCount()).isZero();
    }

    @Test
    @DisplayName("ACK_실패시_retryCount를_증가시키고_PENDING을_유지한다")
    void publish_whenAckFails_incrementsRetryCount() {
        ChallengeOutbox outbox = outbox();
        when(outboxRepository.findPendingForUpdate(100)).thenReturn(List.of(outbox));
        when(kafkaTemplate.send("challenge.member.joined", "1", "{\"challengeId\":1}"))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("send failed")));

        poller.publish();

        assertThat(outbox.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(outbox.getRetryCount()).isEqualTo(1);
        assertThat(outbox.getPublishedAt()).isNull();
    }

    @Test
    @DisplayName("ACK_실패후_최대재시도_초과시_FAILED로_변경한다")
    void publish_whenRetryCountExceedsMaxRetry_marksFailed() {
        ChallengeOutbox outbox = outbox();
        ReflectionTestUtils.setField(outbox, "retryCount", 5);
        when(outboxRepository.findPendingForUpdate(100)).thenReturn(List.of(outbox));
        when(kafkaTemplate.send("challenge.member.joined", "1", "{\"challengeId\":1}"))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("send failed")));

        poller.publish();

        assertThat(outbox.getStatus()).isEqualTo(OutboxStatus.FAILED);
        assertThat(outbox.getRetryCount()).isEqualTo(6);
        assertThat(outbox.getPublishedAt()).isNull();
    }

    private ChallengeOutbox outbox() {
        ChallengeOutbox outbox = ChallengeOutbox.create(
                "CHALLENGE",
                1L,
                "challenge.member.joined",
                "{\"challengeId\":1}",
                "CHALLENGE:1:challenge.member.joined:1"
        );
        ReflectionTestUtils.setField(outbox, "id", 10L);
        return outbox;
    }

    private SendResult<String, String> sendResult() {
        ProducerRecord<String, String> producerRecord = new ProducerRecord<>(
                "challenge.member.joined",
                "1",
                "{\"challengeId\":1}"
        );
        RecordMetadata metadata = new RecordMetadata(
                new TopicPartition("challenge.member.joined", 0),
                0,
                42,
                0,
                1,
                17
        );
        return new SendResult<>(producerRecord, metadata);
    }
}
