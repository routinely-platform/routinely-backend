package com.routinely.challenge_service.application.routine;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * completed/cancelled 공통 계약. 개인 루틴은 challengeId 및 랭킹 값이 없다.
 *
 * <p>{@code acceptedCount} · {@code reachedAt} · {@code revision}은 한 벌의 완전한 스냅샷이다.
 * {@code reachedAt}은 현재 남은 완료 기록으로 현재 인정 횟수를 채운 시각(UTC ISO-8601)이며 0회면 null이다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RoutineExecutionCompletedPayload(
        String eventId, String occurredAt, Long challengeId, Long userId,
        Integer acceptedCount, String reachedAt, Long revision
) {}
