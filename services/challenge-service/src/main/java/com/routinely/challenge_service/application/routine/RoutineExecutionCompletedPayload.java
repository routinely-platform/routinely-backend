package com.routinely.challenge_service.application.routine;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** completed/cancelled 공통 계약. 개인 루틴은 challengeId 및 랭킹 값이 없다. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RoutineExecutionCompletedPayload(
        String eventId, String occurredAt, Long challengeId, Long userId,
        Integer acceptedCount, Long revision
) {}
