package com.routinely.challenge_service.domain.challenge;

import java.util.List;

public record MyChallengeSearchCondition(
        String keyword,
        List<String> categoryCodes,
        ChallengeLifecycleStatus status,
        MyChallengeSort sort
) {
    public MyChallengeSearchCondition {
        keyword = normalizeKeyword(keyword);
        categoryCodes = normalizeCategoryCodes(categoryCodes);
        if (sort == null) sort = MyChallengeSort.RECENT_JOIN;
    }

    public boolean hasEndedStatusWithEndImminentSort() {
        return status == ChallengeLifecycleStatus.ENDED && sort == MyChallengeSort.END_IMMINENT;
    }

    private static String normalizeKeyword(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return null;
        }
        return keyword.trim();
    }

    private static List<String> normalizeCategoryCodes(List<String> categoryCodes) {
        if (categoryCodes == null) {
            return List.of();
        }

        return categoryCodes.stream()
                .filter(code -> code != null && !code.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
    }
}
