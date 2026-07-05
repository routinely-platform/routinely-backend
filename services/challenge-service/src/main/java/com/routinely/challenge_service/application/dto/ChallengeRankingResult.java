package com.routinely.challenge_service.application.dto;

import java.util.List;

/**
 * 챌린지 랭킹 조회 결과.
 *
 * @param rankings     달성률 내림차순 상위 N명 목록. 같은 달성률은 같은 rank를 가진다.
 * @param myRanking    요청자 본인의 랭킹 — 상위 목록 포함 여부와 무관하게 항상 채운다.
 * @param totalMembers 랭킹에 집계된 전체 멤버 수
 */
public record ChallengeRankingResult(
        List<Entry> rankings,
        Entry myRanking,
        long totalMembers
) {
    /**
     * @param rank            1-based 공동 등수 (나보다 높은 달성률의 멤버 수 + 1)
     * @param userId          사용자 ID
     * @param achievementRate 달성률 (%)
     * @param isMe            요청자 본인 여부
     */
    public record Entry(int rank, Long userId, double achievementRate, boolean isMe) {}
}
