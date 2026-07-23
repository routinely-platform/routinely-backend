package com.routinely.routine_service.application.template.dto;

import com.routinely.routine_service.domain.template.RoutineTemplate;
import com.routinely.routine_service.domain.template.ScheduleType;

public record RoutineTemplateResult(
        Long templateId,
        String title,
        String categoryCode,
        ScheduleType scheduleType,
        Short daysOfWeek,
        Integer targetCount,
        Long challengeId) {

    public static RoutineTemplateResult from(RoutineTemplate template) {
        return new RoutineTemplateResult(
                template.getId(),
                template.getTitle(),
                template.getCategoryCode(),
                template.getScheduleType(),
                template.getDaysOfWeek(),
                template.getTargetCount(),
                template.getChallengeId()
        );
    }
}
