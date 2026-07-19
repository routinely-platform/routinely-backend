package com.routinely.routine_service.presentation.rest.template.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.routinely.routine_service.application.template.dto.CreateRoutineTemplateCommand;
import com.routinely.routine_service.domain.template.RepeatType;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateRoutineTemplateRequest(
        @NotBlank(message = "루틴 이름은 필수입니다.")
        @Size(max = 100, message = "루틴 이름은 100자 이하여야 합니다.")
        String title,

        @NotBlank(message = "카테고리 코드는 필수입니다.")
        @Size(max = 30, message = "카테고리 코드는 30자 이하여야 합니다.")
        String categoryCode,

        @NotBlank(message = "반복 유형은 필수입니다.")
        @Pattern(regexp = "^(DAILY|DAILY_N|WEEKLY|WEEKLY_N|MONTHLY_N)$", message = "올바르지 않은 반복 유형입니다.")
        String repeatType,

        @Min(value = 1, message = "반복 횟수는 1 이상이어야 합니다.")
        Integer repeatValue) {

    private static final String REPEAT_TYPE_PATTERN = "^(DAILY|DAILY_N|WEEKLY|WEEKLY_N|MONTHLY_N)$";

    // repeat_value는 DAILY_N/WEEKLY_N/MONTHLY_N에서만 의미가 있다 (routine_templates.ck_rt_repeat_value 미러링).
    @JsonIgnore
    @AssertTrue(message = "반복 횟수는 DAILY_N/WEEKLY_N/MONTHLY_N에서만 지정할 수 있으며, 해당 유형에서는 필수입니다.")
    public boolean isRepeatValueValid() {
        if (repeatType == null || !repeatType.matches(REPEAT_TYPE_PATTERN)) {
            return true; // repeatType 누락/형식 오류는 @NotBlank/@Pattern이 처리한다.
        }
        boolean valueRequired = RepeatType.valueOf(repeatType).requiresValue();
        return valueRequired ? repeatValue != null : repeatValue == null;
    }

    public CreateRoutineTemplateCommand toCommand(Long userId) {
        return new CreateRoutineTemplateCommand(
                userId, title, categoryCode, RepeatType.valueOf(repeatType), repeatValue);
    }
}
