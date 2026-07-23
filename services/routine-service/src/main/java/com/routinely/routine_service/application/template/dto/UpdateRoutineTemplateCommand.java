package com.routinely.routine_service.application.template.dto;

import com.routinely.routine_service.domain.template.ScheduleType;

/**
 * 루틴 템플릿 부분 수정 커맨드 — null 필드는 변경하지 않는다.
 * 스케줄은 유형·요일·횟수를 한 묶음으로만 전달된다(scheduleType != null이면 스케줄 변경). (요청 DTO에서 보장)
 */
public record UpdateRoutineTemplateCommand(
        Long templateId,
        Long userId,
        String title,
        String categoryCode,
        ScheduleType scheduleType,
        Short daysOfWeek,
        Integer targetCount) {

    public boolean hasScheduleChange() {
        return scheduleType != null;
    }
}
