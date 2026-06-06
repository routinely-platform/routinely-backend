package com.routinely.challenge_service.presentation.rest.challenge.dto.response;

import com.routinely.challenge_service.application.dto.ChallengeMemberResult;
import com.routinely.challenge_service.domain.member.ChallengeMemberRole;
import com.routinely.challenge_service.domain.member.MembershipStatus;

import java.time.LocalDateTime;

public record ChallengeMemberResponse(
        Long challengeMemberId,
        Long challengeId,
        Long userId,
        ChallengeMemberRole role,
        MembershipStatus status,
        LocalDateTime joinedAt
) {

    public static ChallengeMemberResponse from(ChallengeMemberResult result) {
        return new ChallengeMemberResponse(
                result.challengeMemberId(),
                result.challengeId(),
                result.userId(),
                result.role(),
                result.status(),
                result.joinedAt()
        );
    }
}
