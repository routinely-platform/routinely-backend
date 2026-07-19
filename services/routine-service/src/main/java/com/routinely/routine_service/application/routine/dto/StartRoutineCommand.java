package com.routinely.routine_service.application.routine.dto;

import java.time.LocalDate;
import java.time.LocalTime;

public record StartRoutineCommand(
        Long userId,
        Long routineTemplateId,
        LocalDate startedAt,
        LocalDate endedAt,
        LocalTime preferredTime) {
}
