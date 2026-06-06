package com.routinely.challenge_service.application.dto;

import com.routinely.challenge_service.domain.member.ChallengeMember;
import com.routinely.challenge_service.domain.member.ChallengeMemberRole;
import com.routinely.challenge_service.domain.member.MembershipStatus;

import java.time.LocalDateTime;

public record ChallengeMemberResult(
        Long challengeMemberId,
        Long challengeId,
        Long userId,
        ChallengeMemberRole role,
        MembershipStatus status,
        LocalDateTime joinedAt
) {

    public static ChallengeMemberResult from(ChallengeMember member) {
        return new ChallengeMemberResult(
                member.getId(),
                member.getChallenge().getId(),
                member.getUserId(),
                member.getRole(),
                member.getStatus(),
                member.getJoinedAt()
        );
    }
}
