package com.routinely.routine_service.presentation.rest.routine.dto.request;

import com.routinely.routine_service.domain.template.Weekdays;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Pattern;
import com.fasterxml.jackson.annotation.JsonIgnore;

import java.time.LocalTime;
import java.util.List;

/**
 * 루틴 선호 설정(선호 시각 + 선호 요일) 요청. 요청 본문의 값이 그대로 새 값이 된다 —
 * 각 필드가 null이면 해당 설정을 해제한다. 요일은 완료를 제약하지 않는 soft 선호다. (ADR-0035, ADR-0039)
 */
public record UpdateRoutinePreferencesRequest(
        @Pattern(
                regexp = "^([01]\\d|2[0-3]):[0-5]\\d:[0-5]\\d$",
                message = "선호 수행 시각은 HH:mm:ss 형식이어야 합니다."
        )
        String preferredTime,

        List<String> preferredDays) {

    @JsonIgnore
    @AssertTrue(message = "선호 요일은 MON~SUN 코드여야 합니다.")
    public boolean isPreferredDaysValid() {
        return preferredDays == null || preferredDays.stream().allMatch(Weekdays::isValidCode);
    }

    public LocalTime toPreferredTime() {
        return preferredTime == null ? null : LocalTime.parse(preferredTime);
    }

    public Short toPreferredDays() {
        return preferredDays == null || preferredDays.isEmpty() ? null : Weekdays.toBitmask(preferredDays);
    }
}
