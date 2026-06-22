package com.routinely.routine_service.infrastructure.kafka;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.routinely.routine_service.domain.inbox.InboxStatus;
import com.routinely.routine_service.domain.inbox.RoutineInbox;
import com.routinely.routine_service.domain.inbox.RoutineInboxRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("ChallengeCreatedConsumer")
class ChallengeCreatedConsumerTest {

    private static final String MESSAGE = """
            {"eventId":"evt-1","occurredAt":"2026-06-22T00:00:00Z","challengeId":42,\
            "creatorUserId":7,"categoryCode":"EXERCISE","routineTitle":"아침 러닝 30분",\
            "repeatType":"DAILY","repeatValue":null,"startedAt":"2026-06-25","endedAt":"2026-07-24"}""";

    private RoutineInboxRepository inboxRepository;
    private ChallengeCreatedConsumer consumer;

    @BeforeEach
    void setUp() {
        inboxRepository = mock(RoutineInboxRepository.class);
        consumer = new ChallengeCreatedConsumer(inboxRepository, JsonMapper.builder().findAndAddModules().build());
    }

    @Test
    @DisplayName("정상_수신시_RECEIVED상태로_Inbox에_저장한다")
    void consume_whenNewMessage_savesInboxAsReceived() {
        when(inboxRepository.existsByMessageId("evt-1")).thenReturn(false);

        consumer.consume(MESSAGE);

        ArgumentCaptor<RoutineInbox> captor = ArgumentCaptor.forClass(RoutineInbox.class);
        verify(inboxRepository).save(captor.capture());
        RoutineInbox saved = captor.getValue();
        assertThat(saved.getMessageId()).isEqualTo("evt-1");
        assertThat(saved.getEventType()).isEqualTo("challenge.created");
        assertThat(saved.getStatus()).isEqualTo(InboxStatus.RECEIVED);
        assertThat(saved.getAggregateType()).isEqualTo("CHALLENGE");
        assertThat(saved.getAggregateId()).isEqualTo(42L);
        assertThat(saved.getPayload()).isEqualTo(MESSAGE);
    }

    @Test
    @DisplayName("중복_수신시_저장하지_않고_무시한다")
    void consume_whenDuplicateMessage_skipsSave() {
        when(inboxRepository.existsByMessageId("evt-1")).thenReturn(true);

        consumer.consume(MESSAGE);

        verify(inboxRepository, never()).save(any(RoutineInbox.class));
    }

    @Test
    @DisplayName("동시_수신_race로_저장시_UNIQUE위반이_발생해도_예외없이_무시하고_ACK한다")
    void consume_whenSaveViolatesUniqueConstraint_swallowsAsDuplicate() {
        when(inboxRepository.existsByMessageId("evt-1")).thenReturn(false, true);
        when(inboxRepository.save(any(RoutineInbox.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate key value violates unique constraint"));

        assertThatNoException().isThrownBy(() -> consumer.consume(MESSAGE));

        verify(inboxRepository).save(any(RoutineInbox.class));
    }

    @Test
    @DisplayName("저장시_중복이_아닌_DB제약위반은_삼키지_않고_예외를_전파한다")
    void consume_whenSaveViolatesNonDuplicateConstraint_rethrows() {
        when(inboxRepository.existsByMessageId("evt-1")).thenReturn(false, false);
        when(inboxRepository.save(any(RoutineInbox.class)))
                .thenThrow(new DataIntegrityViolationException("not-null property references a null value"));

        assertThatThrownBy(() -> consumer.consume(MESSAGE))
                .isInstanceOf(DataIntegrityViolationException.class);

        verify(inboxRepository).save(any(RoutineInbox.class));
    }
}
