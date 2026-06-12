package com.routinely.challenge_service.application.dto;

import com.routinely.challenge_service.domain.member.ChallengeMemberRole;

public record MembershipCheckResult(
        boolean activeMember,
        String memberRole
) {

    public static MembershipCheckResult activeMember(ChallengeMemberRole role) {
        return new MembershipCheckResult(true, role.name());
    }

    public static MembershipCheckResult notMember() {
        return new MembershipCheckResult(false, "");
    }
}
