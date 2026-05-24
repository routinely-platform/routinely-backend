package com.routinely.challenge_service.application.dto;

import com.routinely.challenge_service.domain.Challenge;
import com.routinely.challenge_service.domain.ChallengeLifecycleStatus;
import com.routinely.challenge_service.domain.ChallengeMemberRole;

import java.time.LocalDate;
import java.time.LocalDateTime;

public record ChallengeResult(
        Long challengeId,
        String title,
        String description,
        String categoryCode,
        boolean isPublic,
        String inviteCode,
        int maxMembers,
        ChallengeLifecycleStatus status,
        LocalDate startedAt,
        LocalDate endedAt,
        int currentMembers,
        LocalDateTime createdAt,
        Long creatorUserId,
        ChallengeMemberRole myRole) {

    public static ChallengeResult from(
            Challenge challenge,
            int currentMembers,
            String inviteCode,
            ChallengeMemberRole myRole
    ) {
        return new ChallengeResult(
                challenge.getId(),
                challenge.getTitle(),
                challenge.getDescription(),
                challenge.getCategoryCode(),
                challenge.isPublic(),
                inviteCode,
                challenge.getMaxMembers(),
                challenge.getStatus(),
                challenge.getStartedAt(),
                challenge.getEndedAt(),
                currentMembers,
                challenge.getCreatedAt(),
                challenge.getCreatorUserId(),
                myRole
        );
    }
}
