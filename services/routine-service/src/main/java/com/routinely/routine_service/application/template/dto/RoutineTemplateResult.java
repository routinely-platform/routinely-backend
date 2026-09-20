package com.routinely.routine_service.application.template.dto;

import com.routinely.routine_service.domain.definition.RoutineDefinition;
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
        RoutineDefinition definition = template.getDefinition();
        return new RoutineTemplateResult(
                template.getId(),
                definition.getTitle(),
                definition.getCategoryCode(),
                definition.getScheduleType(),
                definition.getDaysOfWeek(),
                definition.getTargetCount(),
                template.getChallengeId()
        );
    }
}
