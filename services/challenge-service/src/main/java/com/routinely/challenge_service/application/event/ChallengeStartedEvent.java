package com.routinely.challenge_service.application.event;

public record ChallengeStartedEvent(
        String eventId,
        String occurredAt,
        Long challengeId,
        String challengeName,
        String startedAt,
        String endedAt
) {}
