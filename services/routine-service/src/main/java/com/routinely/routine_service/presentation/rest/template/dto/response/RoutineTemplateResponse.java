package com.routinely.routine_service.presentation.rest.template.dto.response;

import com.routinely.routine_service.application.template.dto.RoutineTemplateResult;
import com.routinely.routine_service.domain.template.Weekdays;

import java.util.List;

public record RoutineTemplateResponse(
        Long templateId,
        String title,
        String categoryCode,
        String scheduleType,
        List<String> daysOfWeek,
        Integer targetCount,
        Long challengeId) {

    public static RoutineTemplateResponse from(RoutineTemplateResult result) {
        List<String> days = Weekdays.toCodes(result.daysOfWeek());
        return new RoutineTemplateResponse(
                result.templateId(),
                result.title(),
                result.categoryCode(),
                result.scheduleType().name(),
                days.isEmpty() ? null : days,
                result.targetCount(),
                result.challengeId()
        );
    }
}
