package com.routinely.routine_service.application.routine.dto;
import java.time.LocalDate;
import java.time.LocalTime;
public record UpdateRoutineCommand(String title, String categoryCode, String scheduleType,
        Short daysOfWeek, Integer targetCount, LocalDate startedAt, LocalDate endedAt,
        LocalTime preferredTime, Short preferredDays, boolean clearEndedAt,
        boolean clearPreferredTime, boolean clearPreferredDays) {
    public boolean changesDefinitionOrPeriod() {
        return title != null || categoryCode != null || scheduleType != null
            || startedAt != null || endedAt != null || clearEndedAt;
    }
}
