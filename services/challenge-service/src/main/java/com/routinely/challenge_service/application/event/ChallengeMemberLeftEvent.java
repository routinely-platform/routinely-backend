package com.routinely.challenge_service.application.event;

public record ChallengeMemberLeftEvent(
        String eventId,
        String occurredAt,
        Long challengeId,
        Long userId,
        String reason
) {}
