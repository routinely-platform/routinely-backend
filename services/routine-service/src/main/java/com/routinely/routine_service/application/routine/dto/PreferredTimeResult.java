package com.routinely.routine_service.application.routine.dto;

import com.routinely.routine_service.domain.routine.Routine;

import java.time.LocalTime;

/**
 * 선호 수행 시각 변경 결과 — 응답이 routineId와 preferredTime만 필요하므로 최소 필드만 담는다.
 */
public record PreferredTimeResult(
        Long routineId,
        LocalTime preferredTime) {

    public static PreferredTimeResult from(Routine routine) {
        return new PreferredTimeResult(routine.getId(), routine.getPreferredTime());
    }
}
