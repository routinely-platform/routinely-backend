package com.routinely.challenge_service.application.dto;

import java.time.LocalDate;

public record CreateChallengeCommand(
        String title,
        String description,
        boolean isPublic,
        int maxMembers,
        String categoryCode,
        LocalDate startedAt,
        LocalDate endedAt) {

    // TODO: #48 - inviteeUserIds(List<Long>) 필드 추가 — ChallengeCreatedEvent payload에 포함, 초대 알림 발송에 사용
}
