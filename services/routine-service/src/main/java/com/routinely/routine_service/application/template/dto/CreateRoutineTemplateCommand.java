package com.routinely.routine_service.application.template.dto;

import com.routinely.core.schedule.ScheduleType;

public record CreateRoutineTemplateCommand(
        Long userId,
        String title,
        String categoryCode,
        ScheduleType scheduleType,
        Short daysOfWeek,
        Integer targetCount) {
}
