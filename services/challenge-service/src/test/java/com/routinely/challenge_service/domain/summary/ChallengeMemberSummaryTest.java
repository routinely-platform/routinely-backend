package com.routinely.challenge_service.domain.summary;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ChallengeMemberSummary — 인정 횟수와 동점 나열 시각")
class ChallengeMemberSummaryTest {

    private static final LocalDateTime WED = LocalDateTime.of(2026, 9, 23, 9, 0);
    private static final LocalDateTime FRI = LocalDateTime.of(2026, 9, 25, 9, 0);

    @Test
    @DisplayName("인정 횟수가 늘면 도달 시각을 옮긴다")
    void applyAcceptedCount_increased_movesLastCompletedAt() {
        var summary = ChallengeMemberSummary.create(1L, 1L);
        summary.applyAcceptedCount(3, 1, WED);

        assertThat(summary.getAcceptedCount()).isEqualTo(3);
        assertThat(summary.getLastCompletedAt()).isEqualTo(WED);
    }

    @Test
    @DisplayName("캡을 넘긴 완료로 인정 횟수가 그대로면 도달 시각을 유지한다")
    void applyAcceptedCount_unchanged_keepsLastCompletedAt() {
        var summary = ChallengeMemberSummary.create(1L, 1L);
        summary.applyAcceptedCount(3, 1, WED);

        summary.applyAcceptedCount(3, 2, FRI);

        assertThat(summary.getRevision()).isEqualTo(2);
        assertThat(summary.getLastCompletedAt()).isEqualTo(WED);
    }

    @Test
    @DisplayName("취소로 인정 횟수가 줄어도 도달 시각을 늦추지 않는다")
    void applyAcceptedCount_decreased_keepsLastCompletedAt() {
        var summary = ChallengeMemberSummary.create(1L, 1L);
        summary.applyAcceptedCount(3, 1, WED);

        summary.applyAcceptedCount(2, 2, FRI);

        assertThat(summary.getAcceptedCount()).isEqualTo(2);
        assertThat(summary.getLastCompletedAt()).isEqualTo(WED);
    }

    @Test
    @DisplayName("revision이 같거나 낮으면 아무것도 바꾸지 않는다")
    void applyAcceptedCount_staleRevision_ignored() {
        var summary = ChallengeMemberSummary.create(1L, 1L);
        summary.applyAcceptedCount(3, 5, WED);

        summary.applyAcceptedCount(9, 5, FRI);

        assertThat(summary.getAcceptedCount()).isEqualTo(3);
        assertThat(summary.getLastCompletedAt()).isEqualTo(WED);
    }
}
