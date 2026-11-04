package com.routinely.routine_service.infrastructure.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.routinely.core.exception.BusinessException;
import com.routinely.routine_service.domain.inbox.InboxStatus;
import com.routinely.routine_service.domain.inbox.RoutineInbox;
import com.routinely.routine_service.domain.inbox.RoutineInboxRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
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

@ExtendWith(OutputCaptureExtension.class)
@DisplayName("ChallengeStartedConsumer")
class ChallengeStartedConsumerTest {

    private static final String MESSAGE = """
            {"eventId":"evt-1","occurredAt":"2026-11-10T00:00:00Z","challengeId":5,\
            "challengeName":"30일 러닝 챌린지","startedAt":"2026-11-10","endedAt":"2026-12-09",\
            "routineTitle":"저녁 러닝 30분","categoryCode":"EXERCISE","scheduleType":"SPECIFIC_DAYS",\
            "daysOfWeek":["MON","WED","FRI"],"targetCount":null,"leaderUserId":100,\
            "members":[{"userId":100,"joinedAt":"2026-11-01"},{"userId":200,"joinedAt":"2026-11-02"}]}""";

    private RoutineInboxRepository inboxRepository;
    private ChallengeStartedConsumer consumer;

    @BeforeEach
    void setUp() {
        inboxRepository = mock(RoutineInboxRepository.class);
        consumer = new ChallengeStartedConsumer(inboxRepository, new ObjectMapper());
    }

    @Test
    @DisplayName("정상_수신시_원문을_RECEIVED_상태로_Inbox에_저장한다")
    void consume_success() {
        when(inboxRepository.existsByMessageId("evt-1")).thenReturn(false);

        consumer.consume(MESSAGE);

        ArgumentCaptor<RoutineInbox> captor = ArgumentCaptor.forClass(RoutineInbox.class);
        verify(inboxRepository).save(captor.capture());
        RoutineInbox saved = captor.getValue();
        assertThat(saved.getMessageId()).isEqualTo("evt-1");
        assertThat(saved.getEventType()).isEqualTo("challenge.started");
        assertThat(saved.getStatus()).isEqualTo(InboxStatus.RECEIVED);
        assertThat(saved.getAggregateType()).isEqualTo("CHALLENGE");
        assertThat(saved.getAggregateId()).isEqualTo(5L);
        assertThat(saved.getPayload()).isEqualTo(MESSAGE);
    }

    @Test
    @DisplayName("필수_필드가_빠진_메시지도_리스너는_따지지_않고_저장한다")
    void consume_missingRequiredFields_storesAnyway() {
        String withoutEndedAt = """
                {"eventId":"evt-2","challengeId":5,"routineTitle":"저녁 러닝 30분","members":[]}""";
        when(inboxRepository.existsByMessageId("evt-2")).thenReturn(false);

        consumer.consume(withoutEndedAt);

        verify(inboxRepository).save(any(RoutineInbox.class));
    }

    @Test
    @DisplayName("중복_수신시_저장하지_않고_무시한다")
    void consume_duplicate_skipsSave() {
        when(inboxRepository.existsByMessageId("evt-1")).thenReturn(true);

        consumer.consume(MESSAGE);

        verify(inboxRepository, never()).save(any(RoutineInbox.class));
    }

    @Test
    @DisplayName("동시_수신_race로_UNIQUE_위반이_나면_재조회_후_무시하고_ACK한다")
    void consume_concurrentDuplicate_swallows() {
        when(inboxRepository.existsByMessageId("evt-1")).thenReturn(false, true);
        when(inboxRepository.save(any(RoutineInbox.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate key"));

        assertThatNoException().isThrownBy(() -> consumer.consume(MESSAGE));
    }

    @Test
    @DisplayName("중복이_아닌_DB_제약_위반은_삼키지_않는다")
    void consume_otherConstraintViolation_rethrows() {
        when(inboxRepository.existsByMessageId("evt-1")).thenReturn(false, false);
        when(inboxRepository.save(any(RoutineInbox.class)))
                .thenThrow(new DataIntegrityViolationException("not-null"));

        assertThatThrownBy(() -> consumer.consume(MESSAGE))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("JSON으로_읽을_수_없는_메시지는_저장하지_않고_예외를_던진다")
    void consume_unparsable_throwsException() {
        assertThatThrownBy(() -> consumer.consume("{not-json"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("역직렬화");

        verify(inboxRepository, never()).save(any(RoutineInbox.class));
    }

    @Test
    @DisplayName("eventId가_없는_메시지는_멱등성_키가_없어_저장하지_않고_예외를_던진다")
    void consume_withoutEventId_throwsException() {
        assertThatThrownBy(() -> consumer.consume("{\"challengeId\":5}"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("eventId");

        verify(inboxRepository, never()).save(any(RoutineInbox.class));
    }

    @Test
    @DisplayName("역직렬화_실패시_받은_문자열_전문을_ERROR_로그에_남긴다")
    void consume_unparsable_logsRawMessage(CapturedOutput output) {
        String broken = "{\"eventId\":\"evt-9\",\"challengeId\":5,\"members\":[{\"userId\":";

        assertThatThrownBy(() -> consumer.consume(broken)).isInstanceOf(BusinessException.class);

        assertThat(output.getOut() + output.getErr())
                .contains("ERROR")
                .contains("[Inbox] 역직렬화 실패")
                .contains(broken);
    }
}
