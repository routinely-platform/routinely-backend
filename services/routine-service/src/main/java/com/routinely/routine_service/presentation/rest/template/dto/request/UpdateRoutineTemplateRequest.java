package com.routinely.routine_service.presentation.rest.template.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.routinely.routine_service.application.template.dto.UpdateRoutineTemplateCommand;
import com.routinely.routine_service.domain.template.RepeatType;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record UpdateRoutineTemplateRequest(
        @Pattern(regexp = ".*\\S.*", message = "루틴 이름은 공백만 입력할 수 없습니다.")
        @Size(max = 100, message = "루틴 이름은 100자 이하여야 합니다.")
        String title,

        @Pattern(regexp = ".*\\S.*", message = "카테고리 코드는 공백만 입력할 수 없습니다.")
        @Size(max = 30, message = "카테고리 코드는 30자 이하여야 합니다.")
        String categoryCode,

        @Pattern(regexp = "^(DAILY|DAILY_N|WEEKLY|WEEKLY_N|MONTHLY_N)$", message = "올바르지 않은 반복 유형입니다.")
        String repeatType,

        @Min(value = 1, message = "반복 횟수는 1 이상이어야 합니다.")
        Integer repeatValue) {

    private static final String REPEAT_TYPE_PATTERN = "^(DAILY|DAILY_N|WEEKLY|WEEKLY_N|MONTHLY_N)$";

    @JsonIgnore
    @AssertTrue(message = "수정할 루틴 템플릿 정보가 하나 이상 필요합니다.")
    public boolean isAnyFieldProvided() {
        return title != null
                || categoryCode != null
                || repeatType != null
                || repeatValue != null;
    }

    // 반복 설정은 항상 쌍으로 변경한다 — repeatValue 단독 수정은 기존 유형과의 정합성을 판단할 수 없다.
    @JsonIgnore
    @AssertTrue(message = "반복 횟수는 반복 유형과 함께 지정해야 합니다.")
    public boolean isRepeatValueProvidedWithType() {
        return repeatValue == null || repeatType != null;
    }

    @JsonIgnore
    @AssertTrue(message = "반복 횟수는 DAILY_N/WEEKLY_N/MONTHLY_N에서만 지정할 수 있으며, 해당 유형에서는 필수입니다.")
    public boolean isRepeatValueValid() {
        if (repeatType == null || !repeatType.matches(REPEAT_TYPE_PATTERN)) {
            return true; // repeatType 형식 오류는 @Pattern이 처리한다.
        }
        boolean valueRequired = RepeatType.valueOf(repeatType).requiresValue();
        return valueRequired ? repeatValue != null : repeatValue == null;
    }

    public UpdateRoutineTemplateCommand toCommand(Long templateId, Long userId) {
        return new UpdateRoutineTemplateCommand(
                templateId,
                userId,
                title,
                categoryCode,
                repeatType == null ? null : RepeatType.valueOf(repeatType),
                repeatValue
        );
    }
}
