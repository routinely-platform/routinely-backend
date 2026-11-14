package com.routinely.challenge_service.infrastructure.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.routinely.challenge_service.domain.inbox.ChallengeInbox;
import com.routinely.challenge_service.domain.inbox.ChallengeInboxRepository;
import com.routinely.core.time.RoutinelyClocks;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

@DisplayName("루틴 랭킹 이벤트 수신")
class ChallengeRankingInboxConsumerTest {
    private static final Clock CLOCK =
            RoutinelyClocks.micros(Clock.fixed(Instant.parse("2026-09-23T00:00:00.123456789Z"), ZoneId.of("Asia/Seoul")));
    private final ChallengeInboxRepository repository = mock(ChallengeInboxRepository.class);
    private final ChallengeRankingInboxConsumer consumer = new ChallengeRankingInboxConsumer(repository, new ObjectMapper(), CLOCK);
    private static final String EVENT = """
            {"eventId":"event-1","occurredAt":"2026-09-23T00:00:00Z","routineId":10,
            "executionId":100,"execDate":"2026-09-20","challengeId":5,"userId":7,"acceptedCount":3,"reachedAt":"2026-09-20T00:00:00Z","revision":42}
            """;

    @Test @DisplayName("취소 이벤트를 같은 Inbox 경로에 저장하고 중복 eventId는 건너뛴다")
    void consumeCancelled_storesOnce() {
        when(repository.existsByMessageId("event-1")).thenReturn(false, true);
        consumer.consumeRoutineExecutionCancelled(EVENT);
        consumer.consumeRoutineExecutionCancelled(EVENT);
        var captor = ArgumentCaptor.forClass(ChallengeInbox.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getEventType()).isEqualTo("routine.execution.cancelled");
        assertThat(captor.getValue().getPayload()).isEqualTo(EVENT);
        // 수신 시각은 주입된 Clock에서 마이크로초로 만든다(#213)
        assertThat(captor.getValue().getReceivedAt()).isEqualTo(LocalDateTime.parse("2026-09-23T09:00:00.123456"));
    }

    @Test @DisplayName("개인 루틴 완료와 취소는 랭킹 Inbox에 넣지 않는다")
    void consume_personal_ignores() {
        String personal = "{\"eventId\":\"personal\",\"challengeId\":null,\"userId\":7}";
        consumer.consumeRoutineExecutionCompleted(personal);
        consumer.consumeRoutineExecutionCancelled(personal);
        verifyNoInteractions(repository);
    }
}
