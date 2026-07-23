package com.routinely.routine_service.presentation.rest.template.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.routinely.routine_service.application.template.dto.UpdateRoutineTemplateCommand;
import com.routinely.routine_service.domain.template.ScheduleType;
import com.routinely.routine_service.domain.template.Weekdays;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

public record UpdateRoutineTemplateRequest(
        @Pattern(regexp = ".*\\S.*", message = "루틴 이름은 공백만 입력할 수 없습니다.")
        @Size(max = 100, message = "루틴 이름은 100자 이하여야 합니다.")
        String title,

        @Pattern(regexp = ".*\\S.*", message = "카테고리 코드는 공백만 입력할 수 없습니다.")
        @Size(max = 30, message = "카테고리 코드는 30자 이하여야 합니다.")
        String categoryCode,

        @Pattern(regexp = ScheduleValidation.TYPE_PATTERN, message = "올바르지 않은 반복 유형입니다.")
        String scheduleType,

        List<String> daysOfWeek,

        @Min(value = 1, message = "목표 횟수는 1 이상이어야 합니다.")
        Integer targetCount) {

    @JsonIgnore
    @AssertTrue(message = "수정할 루틴 템플릿 정보가 하나 이상 필요합니다.")
    public boolean isAnyFieldProvided() {
        return title != null || categoryCode != null
                || scheduleType != null || daysOfWeek != null || targetCount != null;
    }

    // 스케줄은 유형·요일·횟수를 한 묶음으로만 변경한다 — 유형 없이 요일/횟수만 바꾸면 기존 유형과의 정합성을 판단할 수 없다.
    // daysOfWeek가 빈 배열이어도 "제공됨"으로 간주해, 유형 없이 요일만 보내는 no-op(빈 배열 단독)을 막는다.
    @JsonIgnore
    @AssertTrue(message = "요일/목표 횟수는 반복 유형과 함께 지정해야 합니다.")
    public boolean isSchedulePartsProvidedWithType() {
        boolean hasParts = daysOfWeek != null || targetCount != null;
        return !hasParts || scheduleType != null;
    }

    @JsonIgnore
    @AssertTrue(message = ScheduleValidation.MESSAGE)
    public boolean isScheduleValid() {
        if (scheduleType == null) {
            return true; // 스케줄 미변경
        }
        return ScheduleValidation.isValid(scheduleType, daysOfWeek, targetCount);
    }

    public UpdateRoutineTemplateCommand toCommand(Long templateId, Long userId) {
        return new UpdateRoutineTemplateCommand(
                templateId,
                userId,
                title,
                categoryCode,
                scheduleType == null ? null : ScheduleType.valueOf(scheduleType),
                daysOfWeek == null || daysOfWeek.isEmpty() ? null : Weekdays.toBitmask(daysOfWeek),
                targetCount
        );
    }
}
