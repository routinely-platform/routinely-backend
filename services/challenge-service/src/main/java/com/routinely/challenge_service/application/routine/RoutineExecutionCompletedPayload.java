package com.routinely.challenge_service.application.routine;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;

/**
 * routine-service가 발행하는 {@code routine.execution.completed} 이벤트 Payload. (#61, ADR-0028)
 *
 * <p>랭킹 점수 기준인 달성률은 캡 계산(ADR-0027)을 거쳐 산정되는데, 그 계산에는 반복 규칙
 * (schedule_type/target_count)이 필요하다. 반복 규칙은 routine-service의 routine_templates가 소유하므로,
 * challenge-service가 단독으로 재계산할 수 없다. 따라서 routine-service가 챌린지 멤버 기준 집계값
 * ({@code completedCount}, {@code totalScheduled}, {@code achievementRate})까지 계산해 payload에 실어 보내고,
 * challenge-service는 이를 그대로 저장(challenge_member_summary)하고 ZSET에 반영한다.
 *
 * <p>다른 Consumer(routine-service 자체 summary, notification-service 스트릭 판단)가 추가 필드를 쓰더라도
 * 본 Consumer는 필요한 필드만 사용하도록 {@code ignoreUnknown = true}로 둔다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RoutineExecutionCompletedPayload(
        String eventId,
        String occurredAt,
        Long challengeId,
        Long userId,
        Integer completedCount,
        Integer totalScheduled,
        BigDecimal achievementRate
) {}
