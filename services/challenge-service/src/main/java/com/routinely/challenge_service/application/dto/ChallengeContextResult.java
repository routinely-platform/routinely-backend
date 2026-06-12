package com.routinely.challenge_service.application.dto;

import com.routinely.challenge_service.domain.challenge.ChallengeLifecycleStatus;

public record ChallengeContextResult(
        ChallengeLifecycleStatus challengeStatus,
        boolean memberActive
) {
}
