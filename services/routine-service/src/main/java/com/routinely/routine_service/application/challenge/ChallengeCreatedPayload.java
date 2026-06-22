package com.routinely.routine_service.application.challenge;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * challenge-service가 발행하는 {@code challenge.created} 이벤트 Payload. (#132 event-spec.md)
 *
 * <p>routine-service가 추가 RPC 호출 없이 템플릿을 생성할 수 있도록 self-contained 구조이다.
 * preferredTime은 Payload에 포함되지 않으며, 알림 시각은 멤버별 routines 인스턴스에서 설정한다. (ADR-0035)
 *
 * <p>{@code startedAt / endedAt} 은 challenge.started 처리 시 routine_executions 생성에 사용되며
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
        String repeatType,
        Integer repeatValue,
        String startedAt,
        String endedAt
) {}
