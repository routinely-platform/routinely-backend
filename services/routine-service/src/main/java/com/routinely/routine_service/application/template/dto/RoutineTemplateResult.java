package com.routinely.routine_service.application.template.dto;

import com.routinely.routine_service.domain.template.RepeatType;
import com.routinely.routine_service.domain.template.RoutineTemplate;

public record RoutineTemplateResult(
        Long templateId,
        String title,
        String categoryCode,
        RepeatType repeatType,
        Integer repeatValue,
        Long challengeId) {

    public static RoutineTemplateResult from(RoutineTemplate template) {
        return new RoutineTemplateResult(
                template.getId(),
                template.getTitle(),
                template.getCategoryCode(),
                template.getRepeatType(),
                template.getRepeatValue(),
                template.getChallengeId()
        );
    }
}
