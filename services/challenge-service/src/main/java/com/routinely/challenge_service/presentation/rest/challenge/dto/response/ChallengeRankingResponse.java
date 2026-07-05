package com.routinely.challenge_service.presentation.rest.challenge.dto.response;

import com.routinely.challenge_service.application.dto.ChallengeRankingResult;

import java.util.List;

public record ChallengeRankingResponse(
        List<RankEntryResponse> rankings,
        RankEntryResponse myRanking,
        long totalMembers
) {

    public record RankEntryResponse(int rank, Long userId, double achievementRate, boolean isMe) {

        private static RankEntryResponse from(ChallengeRankingResult.Entry entry) {
            return new RankEntryResponse(
                    entry.rank(), entry.userId(), entry.achievementRate(), entry.isMe());
        }
    }

    public static ChallengeRankingResponse from(ChallengeRankingResult result) {
        List<RankEntryResponse> rankings = result.rankings().stream()
                .map(RankEntryResponse::from)
                .toList();
        RankEntryResponse myRanking = result.myRanking() != null
                ? RankEntryResponse.from(result.myRanking())
                : null;
        return new ChallengeRankingResponse(rankings, myRanking, result.totalMembers());
    }
}
