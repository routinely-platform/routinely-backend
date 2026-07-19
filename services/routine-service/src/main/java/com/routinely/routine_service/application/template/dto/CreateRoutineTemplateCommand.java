package com.routinely.routine_service.application.template.dto;

import com.routinely.routine_service.domain.template.RepeatType;

public record CreateRoutineTemplateCommand(
        Long userId,
        String title,
        String categoryCode,
        RepeatType repeatType,
        Integer repeatValue) {
}
