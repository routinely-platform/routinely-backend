package com.routinely.challenge_service.domain.challenge;

import java.util.List;

public record ChallengeSearchCondition(
        String keyword,
        List<String> categoryCodes,
        ChallengeSort sort
) {
    public ChallengeSearchCondition {
        keyword = normalizeKeyword(keyword);
        categoryCodes = normalizeCategoryCodes(categoryCodes);
        if (sort == null) sort = ChallengeSort.LATEST;
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
