package com.routinely.routine_service.presentation.rest.routine.dto.response;

import com.routinely.routine_service.application.routine.dto.PreferredTimeResult;

import java.time.format.DateTimeFormatter;

public record RoutinePreferredTimeResponse(
        Long routineId,
        String preferredTime) {

    // LocalTime.toString()은 초가 0이면 "HH:mm"으로 축약되므로, HH:mm:ss 계약을 지키려 명시 포맷한다.
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");

    public static RoutinePreferredTimeResponse from(PreferredTimeResult result) {
        return new RoutinePreferredTimeResponse(
                result.routineId(),
                result.preferredTime() == null ? null : result.preferredTime().format(TIME_FORMAT)
        );
    }
}
