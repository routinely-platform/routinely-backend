package com.routinely.routine_service.application.routine.dto;

import com.routinely.routine_service.domain.routine.Routine;

import java.time.LocalTime;

/**
 * 선호 설정(시각·요일) 변경 결과 — 응답에 필요한 최소 필드만 담는다.
 */
public record PreferencesResult(
        Long routineId,
        LocalTime preferredTime,
        Short preferredDays) {

    public static PreferencesResult from(Routine routine) {
        return new PreferencesResult(routine.getId(), routine.getPreferredTime(), routine.getPreferredDays());
    }
}
