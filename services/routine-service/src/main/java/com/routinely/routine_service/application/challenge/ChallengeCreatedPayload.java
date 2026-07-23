package com.routinely.routine_service.application.challenge;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * challenge-service가 발행하는 {@code challenge.created} 이벤트 Payload. (#132 event-spec.md)
 *
 * <p>routine-service가 추가 RPC 호출 없이 템플릿을 생성할 수 있도록 self-contained 구조이다.
 * 선호 시각·선호 요일은 Payload에 포함되지 않으며 멤버별 routines 인스턴스에서 설정한다. (ADR-0035)
 *
 * <p>챌린지 루틴은 특정 요일 지정(SPECIFIC_DAYS)이 불가하므로 스케줄은 DAILY/WEEKLY_COUNT/MONTHLY_COUNT만
 * 오며, 요일(days_of_week)은 항상 없다. WEEKLY_COUNT/MONTHLY_COUNT일 때만 targetCount가 채워진다. (ADR-0039)
 *
 * <p>{@code startedAt / endedAt} 은 challenge.started 처리 시 routine 실행 기록 생성에 사용되며
 * 템플릿 생성 단계에서는 사용하지 않는다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ChallengeCreatedPayload(
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
