package com.routinely.routine_service.application.routine.dto;

import com.routinely.routine_service.domain.routine.Routine;

import java.time.LocalDate;
import java.time.LocalTime;

public record RoutineResult(
        Long routineId,
        Long routineTemplateId,
        String title,
        Long challengeId,
        LocalDate startedAt,
        LocalDate endedAt,
        LocalTime preferredTime,
        boolean isActive) {

    /**
     * @param title 기반 템플릿의 이름 — routines 테이블에는 없으므로 호출 측이 템플릿에서 조회해 전달한다.
     */
    public static RoutineResult from(Routine routine, String title) {
        return new RoutineResult(
                routine.getId(),
                routine.getRoutineTemplateId(),
                title,
                routine.getChallengeId(),
                routine.getStartedAt(),
                routine.getEndedAt(),
                routine.getPreferredTime(),
                routine.isActive()
        );
    }
}
