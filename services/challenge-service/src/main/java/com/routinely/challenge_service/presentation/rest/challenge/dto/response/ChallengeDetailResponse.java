package com.routinely.challenge_service.presentation.rest.challenge.dto.response;

import com.routinely.challenge_service.application.dto.ChallengeResult;
import com.routinely.challenge_service.domain.challenge.ChallengeLifecycleStatus;
import com.routinely.challenge_service.domain.member.ChallengeMemberRole;

import java.time.LocalDate;

public record ChallengeDetailResponse(
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
        Long creatorUserId,
        ChallengeMemberRole myRole) {

    public static ChallengeDetailResponse from(ChallengeResult result) {
        return new ChallengeDetailResponse(
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
                result.creatorUserId(),
                result.myRole()
        );
    }
}
