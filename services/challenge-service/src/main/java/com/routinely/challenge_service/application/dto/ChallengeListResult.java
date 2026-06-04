package com.routinely.challenge_service.application.dto;

import com.routinely.challenge_service.domain.challenge.Challenge;
import com.routinely.challenge_service.domain.member.ChallengeMember;
import org.springframework.data.domain.Page;

import java.util.List;
import java.util.Map;

public record ChallengeListResult(
        List<ChallengeResult> content,
        int page,
        int size,
        long totalElements,
        int totalPages) {

    public static ChallengeListResult from(Page<Challenge> page, Map<Long, Integer> memberCounts) {
        List<ChallengeResult> results = page.getContent().stream()
                .map(c -> ChallengeResult.from(c, memberCounts.getOrDefault(c.getId(), 0), null, null))
                .toList();
        return new ChallengeListResult(results, page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
    }

    public static ChallengeListResult fromMemberships(
            Page<ChallengeMember> page,
            Map<Long, Integer> memberCounts
    ) {
        List<ChallengeResult> results = page.getContent().stream()
                .map(member -> ChallengeResult.from(
                        member.getChallenge(),
                        memberCounts.getOrDefault(member.getChallenge().getId(), 0),
                        null,
                        member.getRole()
                ))
                .toList();
        return new ChallengeListResult(results, page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
    }
}
