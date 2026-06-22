package com.routinely.routine_service.infrastructure.scheduler;

import com.routinely.routine_service.application.challenge.ChallengeRoutineTemplateService;
import com.routinely.routine_service.domain.inbox.InboxStatus;
import com.routinely.routine_service.domain.inbox.RoutineInboxRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("RoutineInboxScheduler")
class RoutineInboxSchedulerTest {

    private RoutineInboxRepository inboxRepository;
    private ChallengeRoutineTemplateService templateService;
    private RoutineInboxScheduler scheduler;

    @BeforeEach
    void setUp() {
        inboxRepository = mock(RoutineInboxRepository.class);
        templateService = mock(ChallengeRoutineTemplateService.class);
        scheduler = new RoutineInboxScheduler(inboxRepository, templateService);
    }

    @Test
    @DisplayName("RECEIVED_메시지들을_각각_처리하고_성공시_실패기록을_남기지_않는다")
    void processReceivedInbox_processesEachWithoutFailure() {
        when(inboxRepository.findIdsByStatus(eq(InboxStatus.RECEIVED), any(Pageable.class)))
                .thenReturn(List.of(1L, 2L));

        scheduler.processReceivedInbox();

        verify(templateService).processInbox(1L);
        verify(templateService).processInbox(2L);
        verify(templateService, never()).recordFailure(anyLong(), any());
    }

    @Test
    @DisplayName("처리중_예외가_발생하면_실패를_기록하고_다음_메시지를_계속_처리한다")
    void processReceivedInbox_recordsFailureOnExceptionAndContinues() {
        when(inboxRepository.findIdsByStatus(eq(InboxStatus.RECEIVED), any(Pageable.class)))
                .thenReturn(List.of(1L, 2L));
        doThrow(new RuntimeException("처리 실패")).when(templateService).processInbox(1L);

        scheduler.processReceivedInbox();

        verify(templateService).processInbox(1L);
        verify(templateService).recordFailure(1L, "처리 실패");
        verify(templateService).processInbox(2L);
        verify(templateService, never()).recordFailure(eq(2L), any());
    }

    @Test
    @DisplayName("실패_기록_자체가_예외를_던져도_격리되어_다음_메시지_처리를_계속한다")
    void processReceivedInbox_whenRecordFailureThrows_continuesNextMessage() {
        when(inboxRepository.findIdsByStatus(eq(InboxStatus.RECEIVED), any(Pageable.class)))
                .thenReturn(List.of(1L, 2L));
        doThrow(new RuntimeException("처리 실패")).when(templateService).processInbox(1L);
        doThrow(new RuntimeException("기록 실패")).when(templateService).recordFailure(1L, "처리 실패");

        scheduler.processReceivedInbox();

        verify(templateService).recordFailure(1L, "처리 실패");
        verify(templateService).processInbox(2L);
    }

    @Test
    @DisplayName("처리_대상이_없으면_아무_작업도_하지_않는다")
    void processReceivedInbox_whenNoTargets_doesNothing() {
        when(inboxRepository.findIdsByStatus(eq(InboxStatus.RECEIVED), any(Pageable.class)))
                .thenReturn(List.of());

        scheduler.processReceivedInbox();

        verify(templateService, never()).processInbox(anyLong());
        verify(templateService, never()).recordFailure(anyLong(), any());
    }
}
