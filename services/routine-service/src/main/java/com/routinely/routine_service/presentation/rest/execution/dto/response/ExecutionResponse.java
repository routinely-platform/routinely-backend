package com.routinely.routine_service.presentation.rest.execution.dto.response;

import com.routinely.routine_service.application.execution.dto.ExecutionResult;

import java.time.LocalDate;
import java.time.LocalDateTime;

public record ExecutionResponse(
        Long executionId,
        Long routineId,
        String title,
        LocalDate scheduledDate,
        String status,
        LocalDateTime completedAt,
        String photoUrl,
        String memo) {

    public static ExecutionResponse from(ExecutionResult result) {
        return new ExecutionResponse(
                result.executionId(),
                result.routineId(),
                result.title(),
                result.scheduledDate(),
                result.status().name(),
                result.completedAt(),
                result.photoUrl(),
                result.memo()
        );
    }
}
