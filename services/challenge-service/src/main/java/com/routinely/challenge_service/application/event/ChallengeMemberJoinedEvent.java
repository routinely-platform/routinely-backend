package com.routinely.challenge_service.application.event;

public record ChallengeMemberJoinedEvent(
        String eventId,
        String occurredAt,
        Long challengeId,
        String challengeName,
        Long userId,
        String role
) {}
