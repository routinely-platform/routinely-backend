package com.routinely.routine_service.application.inbox;

import com.routinely.core.exception.BusinessException;
import com.routinely.routine_service.domain.inbox.InboxStatus;
import com.routinely.routine_service.domain.inbox.RoutineInbox;
import com.routinely.routine_service.domain.inbox.RoutineInboxRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("RoutineInboxProcessor")
class RoutineInboxProcessorTest {

    private RoutineInboxRepository inboxRepository;
    private RoutineInboxProcessor processor;

    @BeforeEach
    void setUp() {
        inboxRepository = mock(RoutineInboxRepository.class);
        processor = new RoutineInboxProcessor(inboxRepository);
    }

    private RoutineInbox receivedInbox(String eventType) {
        RoutineInbox inbox = RoutineInbox.received("evt-1", eventType, "{}", "CHALLENGE", 42L);
        ReflectionTestUtils.setField(inbox, "id", 1L);
        return inbox;
    }

    @Test
    @DisplayName("처리기가_없는_이벤트는_PROCESSED로_넘기지_않고_예외를_던진다")
    void processInbox_whenNoHandler_throwsAndKeepsReceived() {
        RoutineInbox inbox = receivedInbox("challenge.created");
        when(inboxRepository.findById(1L)).thenReturn(Optional.of(inbox));

        assertThatThrownBy(() -> processor.processInbox(1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("challenge.created");
        assertThat(inbox.getStatus()).isEqualTo(InboxStatus.RECEIVED);
    }

    @Test
    @DisplayName("이미_처리된_메시지는_건너뛴다")
    void processInbox_whenNotReceived_skips() {
        RoutineInbox inbox = receivedInbox("challenge.created");
        inbox.markProcessed(LocalDateTime.of(2026, 6, 22, 9, 0));
        when(inboxRepository.findById(1L)).thenReturn(Optional.of(inbox));

        assertThatCode(() -> processor.processInbox(1L)).doesNotThrowAnyException();
        assertThat(inbox.getStatus()).isEqualTo(InboxStatus.PROCESSED);
    }

    @Test
    @DisplayName("recordFailure는_재시도_한도_이내면_retryCount를_올리고_RECEIVED를_유지한다")
    void recordFailure_whenWithinRetryLimit_incrementsRetryCountAndKeepsReceived() {
        RoutineInbox inbox = receivedInbox("challenge.created");
        when(inboxRepository.findById(1L)).thenReturn(Optional.of(inbox));

        processor.recordFailure(1L, "일시적 DB 오류");

        assertThat(inbox.getRetryCount()).isEqualTo(1);
        assertThat(inbox.getStatus()).isEqualTo(InboxStatus.RECEIVED);
        assertThat(inbox.getLastError()).isEqualTo("일시적 DB 오류");
    }

    @Test
    @DisplayName("recordFailure는_재시도_한도를_초과하면_FAILED로_전이한다")
    void recordFailure_whenExceedsRetryLimit_marksFailed() {
        RoutineInbox inbox = receivedInbox("challenge.created");
        ReflectionTestUtils.setField(inbox, "retryCount", RoutineInboxProcessor.MAX_RETRY);
        when(inboxRepository.findById(1L)).thenReturn(Optional.of(inbox));

        processor.recordFailure(1L, "영구 실패");

        assertThat(inbox.getRetryCount()).isEqualTo(RoutineInboxProcessor.MAX_RETRY + 1);
        assertThat(inbox.getStatus()).isEqualTo(InboxStatus.FAILED);
    }

    @Test
    @DisplayName("recordFailure는_오류메시지가_길면_MAX_ERROR_LENGTH로_잘라_저장한다")
    void recordFailure_whenErrorTooLong_truncates() {
        RoutineInbox inbox = receivedInbox("challenge.created");
        when(inboxRepository.findById(1L)).thenReturn(Optional.of(inbox));
        String longMessage = "x".repeat(RoutineInboxProcessor.MAX_ERROR_LENGTH + 500);

        processor.recordFailure(1L, longMessage);

        assertThat(inbox.getLastError()).hasSize(RoutineInboxProcessor.MAX_ERROR_LENGTH);
    }
}
