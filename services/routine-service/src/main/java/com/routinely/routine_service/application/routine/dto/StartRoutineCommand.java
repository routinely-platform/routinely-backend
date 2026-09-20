package com.routinely.routine_service.application.routine.dto;

import com.routinely.routine_service.domain.definition.RoutineDefinition;
import java.time.LocalDate;
import java.time.LocalTime;

public record StartRoutineCommand(
        Long userId,
        Long routineTemplateId,
        LocalDate startedAt,
        LocalDate endedAt,
        LocalTime preferredTime,
        RoutineDefinition definition) {
    public StartRoutineCommand(Long userId, Long routineTemplateId, LocalDate startedAt,
                               LocalDate endedAt, LocalTime preferredTime) {
        this(userId, routineTemplateId, startedAt, endedAt, preferredTime, null);
    }
}
