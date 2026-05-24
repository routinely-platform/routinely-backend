package com.routinely.challenge_service.presentation.rest.challenge.dto.response;

import com.routinely.challenge_service.application.dto.ChallengeListResult;
import com.routinely.challenge_service.application.dto.ChallengeResult;
import com.routinely.challenge_service.domain.ChallengeLifecycleStatus;
import com.routinely.challenge_service.domain.ChallengeMemberRole;

import java.time.LocalDate;
import java.util.List;

public record ChallengeListResponse(
        List<ChallengeSummaryResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages) {

    public static ChallengeListResponse from(ChallengeListResult result) {
        return new ChallengeListResponse(
                result.content().stream().map(ChallengeSummaryResponse::from).toList(),
                result.page(),
                result.size(),
                result.totalElements(),
                result.totalPages()
        );
    }

    public record ChallengeSummaryResponse(
            Long challengeId,
            String title,
            String description,
            String categoryCode,
            ChallengeLifecycleStatus status,
            int currentMembers,
            int maxMembers,
            LocalDate startedAt,
            LocalDate endedAt,
            ChallengeMemberRole myRole
    ) {

        public static ChallengeSummaryResponse from(ChallengeResult result) {
            return new ChallengeSummaryResponse(
                    result.challengeId(),
                    result.title(),
                    result.description(),
                    result.categoryCode(),
                    result.status(),
                    result.currentMembers(),
                    result.maxMembers(),
                    result.startedAt(),
                    result.endedAt(),
                    result.myRole()
            );
        }
    }
}
