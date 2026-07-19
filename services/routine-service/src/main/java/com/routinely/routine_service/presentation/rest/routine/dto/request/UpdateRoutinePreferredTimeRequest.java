package com.routinely.routine_service.presentation.rest.routine.dto.request;

import jakarta.validation.constraints.Pattern;

import java.time.LocalTime;

/**
 * 선호 수행 시각 설정/변경 요청. {@code preferredTime}이 null이면 설정 해제(리마인더 끄기)로 처리한다. (ADR-0035)
 */
public record UpdateRoutinePreferredTimeRequest(
        @Pattern(
                regexp = "^([01]\\d|2[0-3]):[0-5]\\d:[0-5]\\d$",
                message = "선호 수행 시각은 HH:mm:ss 형식이어야 합니다."
        )
        String preferredTime) {

    public LocalTime toPreferredTime() {
        return preferredTime == null ? null : LocalTime.parse(preferredTime);
    }
}
