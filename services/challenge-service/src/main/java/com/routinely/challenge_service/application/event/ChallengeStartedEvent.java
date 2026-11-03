package com.routinely.challenge_service.application.event;

import java.util.List;

/**
 * 챌린지가 WAITING → ACTIVE로 전이될 때 발행한다 (ADR-0032 §7, ADR-0044 §6.2).
 *
 * <p>routine-service가 추가 RPC 없이 멤버별 루틴을 만들 수 있도록 <b>자기완결적</b>으로 싣는다 —
 * 루틴 정의 5개 · 시작 시점 ACTIVE 멤버 · 방장.
 *
 * <ul>
 *   <li>{@code categoryCode} — 챌린지 카테고리가 루틴 카테고리를 겸한다</li>
 *   <li>{@code daysOfWeek} — MON~SUN 코드 리스트. SPECIFIC_DAYS 외 null</li>
 *   <li>{@code targetCount} — WEEKLY_COUNT/MONTHLY_COUNT 외 null</li>
 *   <li>{@code members[].joinedAt} — 참여일 {@code yyyy-MM-dd}(Asia/Seoul)</li>
 * </ul>
 */
public record ChallengeStartedEvent(
        String eventId,
        String occurredAt,
        Long challengeId,
        String challengeName,
        String startedAt,
        String endedAt,
        String routineTitle,
        String categoryCode,
        String scheduleType,
        List<String> daysOfWeek,
        Integer targetCount,
        Long leaderUserId,
        List<Member> members
) {

    public record Member(Long userId, String joinedAt) {}
}
