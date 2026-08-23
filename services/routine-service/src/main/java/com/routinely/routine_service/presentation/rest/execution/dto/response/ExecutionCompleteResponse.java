package com.routinely.routine_service.presentation.rest.execution.dto.response;

import com.routinely.routine_service.application.execution.dto.ExecutionCompleteResult;

import java.time.LocalDate;
import java.time.LocalDateTime;

public record ExecutionCompleteResponse(
        Long executionId,
        Long routineId,
        LocalDate scheduledDate,
        String status,
        LocalDateTime completedAt,
        String photoUrl) {

    public static ExecutionCompleteResponse from(ExecutionCompleteResult result) {
        return new ExecutionCompleteResponse(
                result.executionId(),
                result.routineId(),
                result.scheduledDate(),
                result.status().name(),
                result.completedAt(),
                result.photoUrl()
        );
    }
}
