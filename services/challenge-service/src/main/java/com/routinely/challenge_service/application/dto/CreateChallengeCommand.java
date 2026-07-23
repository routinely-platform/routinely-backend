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
        // 루틴 정보 — challenge-service는 저장하지 않고 challenge.created 이벤트로 전달한다 (소유권은 routine-service). #132
        // 선호 시각/요일은 챌린지가 정하지 않는다 — 멤버 각자가 본인 routine 인스턴스에서 설정한다 (ADR-0035).
        // 챌린지 루틴은 SPECIFIC_DAYS 불가 → scheduleType은 DAILY/WEEKLY_COUNT/MONTHLY_COUNT (ADR-0039).
        String routineTitle,
        String scheduleType,
        Integer targetCount) {

    // TODO: #48 - inviteeUserIds(List<Long>) 필드 추가 — ChallengeCreatedEvent payload에 포함, 초대 알림 발송에 사용
}
