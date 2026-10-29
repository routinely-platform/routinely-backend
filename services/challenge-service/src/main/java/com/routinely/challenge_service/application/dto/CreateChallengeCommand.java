package com.routinely.challenge_service.application.dto;

import java.time.LocalDate;

public record CreateChallengeCommand(
        String title,
        String description,
        boolean isPublic,
        int maxMembers,
        String categoryCode,
        LocalDate startedAt,
        LocalDate endedAt,
        // 루틴 정의 — challenge-service가 소유하고 challenge.started 페이로드로 전달한다 (ADR-0044, #167).
        // 선호 시각은 챌린지가 정하지 않는다 — 멤버 각자가 본인 routine 인스턴스에서 설정한다 (ADR-0035).
        String routineTitle,
        String scheduleType,
        // 지정 요일 비트마스크(bit0=월 … bit6=일). SPECIFIC_DAYS 전용, 그 외 null.
        Short daysOfWeek,
        Integer targetCount) {
}
