package com.routinely.routine_service.presentation.rest.routine.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.routinely.routine_service.application.routine.dto.StartRoutineCommand;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * 개인 루틴 시작 요청. 챌린지 루틴은 challenge.started 이벤트로 자동 생성되므로 challengeId를 받지 않는다.
 */
public record StartRoutineRequest(
        @NotNull(message = "루틴 템플릿 ID는 필수입니다.")
        Long routineTemplateId,

        @NotNull(message = "시작일은 필수입니다.")
        LocalDate startedAt,

        @NotNull(message = "종료일은 필수입니다.")
        LocalDate endedAt,

        @Pattern(
                regexp = "^([01]\\d|2[0-3]):[0-5]\\d:[0-5]\\d$",
                message = "선호 수행 시각은 HH:mm:ss 형식이어야 합니다."
        )
        String preferredTime) {

    @JsonIgnore
    @AssertTrue(message = "종료일은 시작일보다 빠를 수 없습니다.")
    public boolean isDateRangeValid() {
        return startedAt == null || endedAt == null || !endedAt.isBefore(startedAt);
    }

    public StartRoutineCommand toCommand(Long userId) {
        return new StartRoutineCommand(
                userId,
                routineTemplateId,
                startedAt,
                endedAt,
                preferredTime == null ? null : LocalTime.parse(preferredTime)
        );
    }
}
