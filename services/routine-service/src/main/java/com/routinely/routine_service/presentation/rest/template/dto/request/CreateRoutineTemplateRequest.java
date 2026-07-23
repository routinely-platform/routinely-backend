package com.routinely.routine_service.presentation.rest.template.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.routinely.routine_service.application.template.dto.CreateRoutineTemplateCommand;
import com.routinely.routine_service.domain.template.ScheduleType;
import com.routinely.routine_service.domain.template.Weekdays;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 개인 루틴 템플릿 생성 요청 (ADR-0039).
 *
 * <p>스케줄 유형별 필수 필드가 다르다 — SPECIFIC_DAYS는 daysOfWeek, WEEKLY_COUNT/MONTHLY_COUNT는 targetCount.
 * 요일은 MON~SUN 코드 리스트로 받는다.
 */
public record CreateRoutineTemplateRequest(
        @NotBlank(message = "루틴 이름은 필수입니다.")
        @Size(max = 100, message = "루틴 이름은 100자 이하여야 합니다.")
        String title,

        @NotBlank(message = "카테고리 코드는 필수입니다.")
        @Size(max = 30, message = "카테고리 코드는 30자 이하여야 합니다.")
        String categoryCode,

        @NotBlank(message = "반복 유형은 필수입니다.")
        @Pattern(regexp = ScheduleValidation.TYPE_PATTERN, message = "올바르지 않은 반복 유형입니다.")
        String scheduleType,

        List<String> daysOfWeek,

        @Min(value = 1, message = "목표 횟수는 1 이상이어야 합니다.")
        Integer targetCount) {

    @JsonIgnore
    @jakarta.validation.constraints.AssertTrue(message = ScheduleValidation.MESSAGE)
    public boolean isScheduleValid() {
        return ScheduleValidation.isValid(scheduleType, daysOfWeek, targetCount);
    }

    public CreateRoutineTemplateCommand toCommand(Long userId) {
        return new CreateRoutineTemplateCommand(
                userId,
                title,
                categoryCode,
                ScheduleType.valueOf(scheduleType),
                daysOfWeek == null || daysOfWeek.isEmpty() ? null : Weekdays.toBitmask(daysOfWeek),
                targetCount
        );
    }
}
