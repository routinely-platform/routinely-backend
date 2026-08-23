package com.routinely.routine_service.application.execution.dto;

import com.routinely.routine_service.domain.execution.ExecutionStatus;
import com.routinely.routine_service.domain.execution.RoutineExecution;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 실행 기록 조회 결과 — 저장된 완료 기록과 파생된 미완료 상태를 하나로 표현한다(sparse, ADR-0038).
 *
 * <p>{@code executionId}는 저장된 COMPLETED 행에만 존재하고, 파생된 PENDING/MISSED에서는 null이다.
 * 마찬가지로 completedAt/photoUrl/memo는 완료 기록에서만 채워진다.
 */
public record ExecutionResult(
        Long executionId,
        Long routineId,
        String title,
        LocalDate scheduledDate,
        ExecutionStatus status,
        LocalDateTime completedAt,
        String photoUrl,
        String memo) {

    /**
     * 저장된 완료 기록으로부터 생성한다.
     *
     * @param title 기반 루틴(템플릿) 이름 — 실행 기록에는 없으므로 호출 측이 조회해 전달한다.
     */
    public static ExecutionResult from(RoutineExecution execution, String title) {
        return new ExecutionResult(
                execution.getId(),
                execution.getRoutineId(),
                title,
                execution.getScheduledDate(),
                execution.getStatus(),
                execution.getCompletedAt(),
                execution.getPhotoUrl(),
                execution.getMemo()
        );
    }

    /**
     * 저장되지 않은 파생 상태(PENDING/MISSED)로부터 생성한다 — executionId·인증 정보는 없다.
     */
    public static ExecutionResult derived(Long routineId, String title, LocalDate scheduledDate,
                                          ExecutionStatus status) {
        return new ExecutionResult(null, routineId, title, scheduledDate, status, null, null, null);
    }
}
