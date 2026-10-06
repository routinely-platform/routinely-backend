package com.routinely.challenge_service.domain.summary;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ChallengeMemberSummary — 랭킹 스냅샷 반영")
class ChallengeMemberSummaryTest {

    private static final LocalDateTime WED = LocalDateTime.of(2026, 9, 23, 9, 0);
    private static final LocalDateTime FRI = LocalDateTime.of(2026, 9, 25, 9, 0);
    private static final LocalDateTime SAT = LocalDateTime.of(2026, 9, 26, 10, 0);

    /** routine-service가 발행한 스냅샷 한 건. */
    private record Snapshot(int count, long revision, LocalDateTime reachedAt) {}

    // 주 3회: 월·화·수 완료(수 도달) → 금 캡 초과 → 화 취소(금이 인정되어 금 도달) → 토에 화 몫 재완료(캡 초과)
    private static final Snapshot REACHED_WED = new Snapshot(3, 1, WED);
    private static final Snapshot OVER_CAP_FRI = new Snapshot(3, 2, WED);
    private static final Snapshot CANCEL_TUE = new Snapshot(3, 3, FRI);
    private static final Snapshot RECOMPLETE_SAT = new Snapshot(3, 4, FRI);

    private static ChallengeMemberSummary applyInOrder(List<Snapshot> snapshots) {
        var summary = ChallengeMemberSummary.create(1L, 1L);
        snapshots.forEach(s -> summary.applyAcceptedCount(s.count(), s.revision(), s.reachedAt()));
        return summary;
    }

    private static void assertState(ChallengeMemberSummary summary, int count, long revision, LocalDateTime reachedAt) {
        assertThat(summary.getAcceptedCount()).isEqualTo(count);
        assertThat(summary.getRevision()).isEqualTo(revision);
        assertThat(summary.getLastCompletedAt()).isEqualTo(reachedAt);
    }

    @Test
    @DisplayName("캡 초과 완료 스냅샷을 정순·역순 어느 쪽으로 받아도 도달 시각은 수요일이다")
    void apply_overCap_sameResultRegardlessOfOrder() {
        assertState(applyInOrder(List.of(REACHED_WED, OVER_CAP_FRI)), 3, 2, WED);
        assertState(applyInOrder(List.of(OVER_CAP_FRI, REACHED_WED)), 3, 2, WED);
    }

    @Test
    @DisplayName("중간 이벤트를 놓치고 최신 스냅샷 하나만 받아도 전부 받은 것과 같다")
    void apply_missingMiddleEvents_sameAsFullHistory() {
        var full = applyInOrder(List.of(REACHED_WED, OVER_CAP_FRI, CANCEL_TUE, RECOMPLETE_SAT));
        var latestOnly = applyInOrder(List.of(RECOMPLETE_SAT));
        var shuffled = applyInOrder(List.of(CANCEL_TUE, RECOMPLETE_SAT, REACHED_WED, OVER_CAP_FRI));

        assertState(full, 3, 4, FRI);
        assertState(latestOnly, 3, 4, FRI);
        assertState(shuffled, 3, 4, FRI);
    }

    @Test
    @DisplayName("캡 안 기록을 취소하면 스냅샷이 준 새 도달 시각(금)으로 덮어쓴다")
    void apply_cancelInsideCap_takesPublishedReachedAt() {
        assertState(applyInOrder(List.of(REACHED_WED, CANCEL_TUE)), 3, 3, FRI);
    }

    @Test
    @DisplayName("전부 취소해 0회가 되면 도달 시각도 null로 덮어쓴다")
    void apply_cancelAll_clearsReachedAt() {
        assertState(applyInOrder(List.of(REACHED_WED, new Snapshot(0, 5, null))), 0, 5, null);
    }

    @Test
    @DisplayName("0회까지 내려갔다가 다시 완료하면 새 도달 시각을 따른다")
    void apply_zeroThenRecomplete_takesNewReachedAt() {
        assertState(applyInOrder(List.of(REACHED_WED, new Snapshot(0, 5, null), new Snapshot(1, 6, SAT))), 1, 6, SAT);
    }

    @Test
    @DisplayName("revision이 같거나 낮으면 아무것도 바꾸지 않는다")
    void apply_staleRevision_ignored() {
        var summary = applyInOrder(List.of(CANCEL_TUE));

        summary.applyAcceptedCount(9, 3, SAT);
        summary.applyAcceptedCount(9, 1, SAT);

        assertState(summary, 3, 3, FRI);
    }
}
