package com.routinely.routine_service.application.template.dto;

import com.routinely.routine_service.domain.template.RepeatType;

/**
 * 루틴 템플릿 부분 수정 커맨드 — null 필드는 변경하지 않는다.
 * repeatValue는 반드시 repeatType과 쌍으로만 전달된다. (요청 DTO에서 보장)
 */
public record UpdateRoutineTemplateCommand(
        Long templateId,
        Long userId,
        String title,
        String categoryCode,
        RepeatType repeatType,
        Integer repeatValue) {
}
