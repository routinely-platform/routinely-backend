package com.routinely.routine_service.application.execution;

import java.time.Instant;

/**
 * 챌린지 랭킹 스냅샷 — 캡 적용 누적 인정 횟수와 그 횟수에 도달한 시각.
 *
 * <p>{@code reachedAt}은 "처음 그 점수를 찍었던 역사적 시각"이 아니라 <b>현재 남아 있는 완료 기록으로
 * 현재 인정 횟수를 채운 시각</b> — 인정된 기록 중 마지막 완료 처리 시각이다. 현재 기록만으로 정해지는
 * 값이라 소비자가 중간 이벤트를 놓치거나 역순으로 받아도 최신 스냅샷 하나로 같은 결과가 나온다.
 *
 * @param count     캡 적용 누적 인정 횟수
 * @param reachedAt 도달 시각(UTC Instant). {@code count == 0}이면 {@code null}
 */
public record AcceptedCount(int count, Instant reachedAt) {

    public static final AcceptedCount ZERO = new AcceptedCount(0, null);
}
