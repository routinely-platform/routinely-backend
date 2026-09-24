package com.routinely.challenge_service.infrastructure.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.routinely.challenge_service.domain.inbox.ChallengeInbox;
import com.routinely.challenge_service.domain.inbox.ChallengeInboxRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

@DisplayName("루틴 랭킹 이벤트 수신")
class ChallengeRankingInboxConsumerTest {
    private final ChallengeInboxRepository repository = mock(ChallengeInboxRepository.class);
    private final ChallengeRankingInboxConsumer consumer = new ChallengeRankingInboxConsumer(repository, new ObjectMapper());
    private static final String EVENT = """
            {"eventId":"event-1","occurredAt":"2026-09-23T00:00:00Z","routineId":10,
            "executionId":100,"execDate":"2026-09-20","challengeId":5,"userId":7,"acceptedCount":3,"revision":42}
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
    }

    @Test @DisplayName("개인 루틴 완료와 취소는 랭킹 Inbox에 넣지 않는다")
    void consume_personal_ignores() {
        String personal = "{\"eventId\":\"personal\",\"challengeId\":null,\"userId\":7}";
        consumer.consumeRoutineExecutionCompleted(personal);
        consumer.consumeRoutineExecutionCancelled(personal);
        verifyNoInteractions(repository);
    }
}
