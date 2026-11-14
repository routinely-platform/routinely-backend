package com.routinely.routine_service.application.inbox;

import com.routinely.core.exception.BusinessException;
import com.routinely.routine_service.domain.inbox.InboxStatus;
import com.routinely.routine_service.domain.inbox.RoutineInbox;
import com.routinely.routine_service.domain.inbox.RoutineInboxRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("RoutineInboxProcessor")
class RoutineInboxProcessorTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-11-10T00:00:05Z"), ZoneId.of("Asia/Seoul"));

    private RoutineInboxRepository inboxRepository;
    private RecordingHandler startedHandler;
    private RoutineInboxProcessor processor;

    /** 호출된 Inbox를 기록하는 처리기 — 라우팅만 검증한다. */
    private static final class RecordingHandler implements InboxEventHandler {
        private final String eventType;
        private final RuntimeException failure;
        private final List<RoutineInbox> handled = new ArrayList<>();

        RecordingHandler(String eventType, RuntimeException failure) {
            this.eventType = eventType;
            this.failure = failure;
        }

        @Override
        public String eventType() {
            return eventType;
        }

        @Override
        public void handle(RoutineInbox inbox) {
            handled.add(inbox);
            if (failure != null) {
                throw failure;
            }
        }
    }

    @BeforeEach
    void setUp() {
        inboxRepository = mock(RoutineInboxRepository.class);
        startedHandler = new RecordingHandler("challenge.started", null);
        processor = new RoutineInboxProcessor(inboxRepository,
                List.of(startedHandler, new RecordingHandler("challenge.member.left", null)), CLOCK);
    }

    private RoutineInbox receivedInbox(String eventType) {
        RoutineInbox inbox = RoutineInbox.received("evt-1", eventType, "{}", "CHALLENGE", 42L, LocalDateTime.now(CLOCK));
        ReflectionTestUtils.setField(inbox, "id", 1L);
        return inbox;
    }

    @Test
    @DisplayName("eventType에_맞는_처리기로_위임하고_PROCESSED로_전이한다")
    void processInbox_routesToHandlerAndMarksProcessed() {
        RoutineInbox inbox = receivedInbox("challenge.started");
        when(inboxRepository.findById(1L)).thenReturn(Optional.of(inbox));

        processor.processInbox(1L);

        assertThat(startedHandler.handled).containsExactly(inbox);
        assertThat(inbox.getStatus()).isEqualTo(InboxStatus.PROCESSED);
        assertThat(inbox.getProcessedAt()).isEqualTo(LocalDateTime.of(2026, 11, 10, 9, 0, 5));
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
        assertThat(startedHandler.handled).isEmpty();
    }

    @Test
    @DisplayName("처리기가_예외를_던지면_전파하고_PROCESSED로_넘기지_않는다")
    void processInbox_whenHandlerFails_propagatesAndKeepsReceived() {
        RoutineInboxProcessor failing = new RoutineInboxProcessor(inboxRepository,
                List.of(new RecordingHandler("challenge.started", new IllegalStateException("처리 실패"))), CLOCK);
        RoutineInbox inbox = receivedInbox("challenge.started");
        when(inboxRepository.findById(1L)).thenReturn(Optional.of(inbox));

        assertThatThrownBy(() -> failing.processInbox(1L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("처리 실패");
        assertThat(inbox.getStatus()).isEqualTo(InboxStatus.RECEIVED);
    }

    @Test
    @DisplayName("같은_eventType을_맡는_처리기가_둘이면_생성_시점에_실패한다")
    void constructor_whenDuplicateEventType_failsFast() {
        List<InboxEventHandler> duplicated = List.of(
                new RecordingHandler("challenge.started", null),
                new RecordingHandler("challenge.started", null));

        assertThatThrownBy(() -> new RoutineInboxProcessor(inboxRepository, duplicated, CLOCK))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("이미_처리된_메시지는_처리기를_부르지_않고_건너뛴다")
    void processInbox_whenNotReceived_skips() {
        RoutineInbox inbox = receivedInbox("challenge.started");
        inbox.markProcessed(LocalDateTime.of(2026, 6, 22, 9, 0));
        when(inboxRepository.findById(1L)).thenReturn(Optional.of(inbox));

        assertThatCode(() -> processor.processInbox(1L)).doesNotThrowAnyException();
        assertThat(inbox.getStatus()).isEqualTo(InboxStatus.PROCESSED);
        assertThat(startedHandler.handled).isEmpty();
    }

    @Test
    @DisplayName("recordFailure는_재시도_한도_이내면_retryCount를_올리고_RECEIVED를_유지한다")
    void recordFailure_whenWithinRetryLimit_incrementsRetryCountAndKeepsReceived() {
        RoutineInbox inbox = receivedInbox("challenge.started");
        when(inboxRepository.findById(1L)).thenReturn(Optional.of(inbox));

        processor.recordFailure(1L, "일시적 DB 오류");

        assertThat(inbox.getRetryCount()).isEqualTo(1);
        assertThat(inbox.getStatus()).isEqualTo(InboxStatus.RECEIVED);
        assertThat(inbox.getLastError()).isEqualTo("일시적 DB 오류");
    }

    @Test
    @DisplayName("recordFailure는_재시도_한도를_초과하면_FAILED로_전이한다")
    void recordFailure_whenExceedsRetryLimit_marksFailed() {
        RoutineInbox inbox = receivedInbox("challenge.started");
        ReflectionTestUtils.setField(inbox, "retryCount", RoutineInboxProcessor.MAX_RETRY);
        when(inboxRepository.findById(1L)).thenReturn(Optional.of(inbox));

        processor.recordFailure(1L, "영구 실패");

        assertThat(inbox.getRetryCount()).isEqualTo(RoutineInboxProcessor.MAX_RETRY + 1);
        assertThat(inbox.getStatus()).isEqualTo(InboxStatus.FAILED);
    }

    @Test
    @DisplayName("recordFailure는_오류메시지가_길면_MAX_ERROR_LENGTH로_잘라_저장한다")
    void recordFailure_whenErrorTooLong_truncates() {
        RoutineInbox inbox = receivedInbox("challenge.started");
        when(inboxRepository.findById(1L)).thenReturn(Optional.of(inbox));
        String longMessage = "x".repeat(RoutineInboxProcessor.MAX_ERROR_LENGTH + 500);

        processor.recordFailure(1L, longMessage);

        assertThat(inbox.getLastError()).hasSize(RoutineInboxProcessor.MAX_ERROR_LENGTH);
    }
}
