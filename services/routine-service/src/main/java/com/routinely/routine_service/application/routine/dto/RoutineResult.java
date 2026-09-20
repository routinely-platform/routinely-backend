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
        Short preferredDays,
        boolean isActive, String categoryCode, String scheduleType, Short daysOfWeek, Integer targetCount) {

    /**
     * 루틴이 자기 정의를 갖는다 — 제목을 얻으려고 템플릿을 조회하지 않는다 (ADR-0040).
     */
    public static RoutineResult from(Routine routine) {
        return new RoutineResult(
                routine.getId(),
                routine.getRoutineTemplateId(),
                routine.getDefinition().getTitle(),
                routine.getChallengeId(),
                routine.getStartedAt(),
                routine.getEndedAt(),
                routine.getPreferredTime(),
                routine.getPreferredDays(),
                routine.isActive(),
                routine.getDefinition().getCategoryCode(),
                routine.getDefinition().getScheduleType().name(),
                routine.getDefinition().getDaysOfWeek(),
                routine.getDefinition().getTargetCount()
        );
    }
}
