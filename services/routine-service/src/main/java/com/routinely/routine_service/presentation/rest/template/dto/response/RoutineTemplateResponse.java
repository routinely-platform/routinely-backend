package com.routinely.routine_service.presentation.rest.template.dto.response;

import com.routinely.routine_service.application.template.dto.RoutineTemplateResult;

public record RoutineTemplateResponse(
        Long templateId,
        String title,
        String categoryCode,
        String repeatType,
        Integer repeatValue,
        Long challengeId) {

    public static RoutineTemplateResponse from(RoutineTemplateResult result) {
        return new RoutineTemplateResponse(
                result.templateId(),
                result.title(),
                result.categoryCode(),
                result.repeatType().name(),
                result.repeatValue(),
                result.challengeId()
        );
    }
}
