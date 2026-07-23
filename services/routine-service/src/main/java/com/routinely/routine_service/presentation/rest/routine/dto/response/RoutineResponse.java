package com.routinely.routine_service.presentation.rest.routine.dto.response;

import com.routinely.routine_service.application.routine.dto.RoutineResult;
import com.routinely.routine_service.domain.template.Weekdays;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

public record RoutineResponse(
        Long routineId,
        Long routineTemplateId,
        String title,
        Long challengeId,
        LocalDate startedAt,
        LocalDate endedAt,
        String preferredTime,
        List<String> preferredDays,
        boolean isActive) {

    // LocalTime.toString()은 초가 0이면 "HH:mm"으로 축약되므로, HH:mm:ss 계약을 지키려 명시 포맷한다.
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");

    public static RoutineResponse from(RoutineResult result) {
        List<String> days = Weekdays.toCodes(result.preferredDays());
        return new RoutineResponse(
                result.routineId(),
                result.routineTemplateId(),
                result.title(),
                result.challengeId(),
                result.startedAt(),
                result.endedAt(),
                result.preferredTime() == null ? null : result.preferredTime().format(TIME_FORMAT),
                days.isEmpty() ? null : days,
                result.isActive()
        );
    }
}
