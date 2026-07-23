package com.routinely.challenge_service.application.event;

/**
 * 챌린지 생성 시 발행되는 이벤트. (topic: {@code challenge.created})
 * routine-service가 소비해 챌린지 연결 루틴 템플릿(routine_templates)을 생성한다. (ADR-0034)
 * <p>
 * routine-service가 추가 RPC 호출 없이 템플릿을 생성할 수 있도록 self-contained payload를 유지한다.
 * 챌린지 루틴은 SPECIFIC_DAYS 불가 → scheduleType은 DAILY/WEEKLY_COUNT/MONTHLY_COUNT, 요일은 없다.
 * WEEKLY_COUNT/MONTHLY_COUNT일 때만 targetCount가 채워진다. (ADR-0039)
 * 날짜/시각은 기존 이벤트 컨벤션(예: {@link ChallengeStartedEvent})과 동일하게 String으로 직렬화한다.
 */
public record ChallengeCreatedEvent(
        String eventId,
        String occurredAt,
        Long challengeId,
        Long creatorUserId,
        String categoryCode,
        String routineTitle,
        String scheduleType,
        Integer targetCount,
        String startedAt,
        String endedAt
) {}
