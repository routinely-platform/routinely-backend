package com.routinely.challenge_service.presentation.rest.challenge.dto.response;

import com.routinely.challenge_service.application.dto.ChallengeResult;
import com.routinely.challenge_service.domain.ChallengeLifecycleStatus;
import com.routinely.challenge_service.domain.ChallengeMemberRole;

import java.time.LocalDate;
import java.time.LocalDateTime;

public record ChallengeResponse(
        Long challengeId,
        String title,
        String description,
        String categoryCode,
        boolean isPublic,
        String inviteCode,
        int maxMembers,
        int currentMembers,
        ChallengeLifecycleStatus status,
        LocalDate startedAt,
        LocalDate endedAt,
        LocalDateTime createdAt,
        Long creatorUserId,
        ChallengeMemberRole myRole) {

    public static ChallengeResponse from(ChallengeResult result) {
        return new ChallengeResponse(
                result.challengeId(),
                result.title(),
                result.description(),
                result.categoryCode(),
                result.isPublic(),
                result.inviteCode(),
                result.maxMembers(),
                result.currentMembers(),
                result.status(),
                result.startedAt(),
                result.endedAt(),
                result.createdAt(),
                result.creatorUserId(),
                result.myRole()
        );
    }
}
