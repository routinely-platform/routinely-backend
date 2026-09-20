package com.routinely.routine_service.application.execution.dto;

import com.routinely.routine_service.domain.execution.ExecutionStatus;
import com.routinely.routine_service.domain.execution.RoutineExecution;

import com.routinely.routine_service.domain.feed.FeedCard;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 완료 처리 / 완료 취소 결과 — 처리 후 상태만 담는 최소 형태.
 *
 * <p>완료 취소는 행을 삭제하므로(sparse) executionId·completedAt·photoUrl은 없고 상태는 PENDING으로
 * 되돌아간다.
 */
public record ExecutionCompleteResult(
        Long executionId,
        Long routineId,
        LocalDate scheduledDate,
        ExecutionStatus status,
        LocalDateTime completedAt,
        String photoUrl, Long feedCardId) {

    public static ExecutionCompleteResult from(RoutineExecution execution, FeedCard card) {
        return new ExecutionCompleteResult(
                execution.getId(),
                execution.getRoutineId(),
                execution.getScheduledDate(),
                execution.getStatus(),
                execution.getCompletedAt(),
                card == null ? null : card.getPhotoUrl(),
                card == null ? null : card.getId()
        );
    }

    /**
     * 완료 취소 결과 — 행이 삭제되어 PENDING(파생)으로 되돌아간 상태.
     */
    public static ExecutionCompleteResult cancelled(Long routineId, LocalDate scheduledDate) {
        return new ExecutionCompleteResult(
                null, routineId, scheduledDate, ExecutionStatus.PENDING, null, null, null);
    }
}
