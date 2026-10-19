package com.routinely.routine_service.application.execution.dto;

import com.routinely.routine_service.domain.execution.ExecutionStatus;
import com.routinely.routine_service.domain.execution.RoutineExecution;

import com.routinely.routine_service.domain.feed.FeedCard;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 실행 기록 조회 결과 — 저장된 완료 기록과 파생된 미완료 상태를 하나로 표현한다(sparse, ADR-0038).
 *
 * <p>{@code executionId}는 저장된 COMPLETED 행에만 존재하고, 파생된 PENDING/MISSED에서는 null이다.
 * 마찬가지로 completedAt/photoUrl/memo는 완료 기록에서만 채워진다.
 *
 * <p>{@code backfillable}은 "이 날짜를 지금 인증할 수 있는가"다(#58 결정 ⑤). 미래 날짜 · 중단한 루틴은 false,
 * 챌린지 루틴은 백필 창(오늘 + 지난 N일) 밖이면 false. COMPLETED 행도 같은 규칙으로 계산한다 — 취소 후 다시
 * 인증할 수 있는지를 미리 알려 준다. 프론트는 이 값으로 버튼만 켜고 끄며 날짜 계산을 하지 않는다.
 */
public record ExecutionResult(
        Long executionId,
        Long routineId,
        String title,
        LocalDate scheduledDate,
        ExecutionStatus status,
        LocalDateTime completedAt,
        String photoUrl,
        String memo, Long feedCardId,
        boolean backfillable) {

    /**
     * 저장된 완료 기록으로부터 생성한다.
     *
     * @param title 기반 루틴(템플릿) 이름 — 실행 기록에는 없으므로 호출 측이 조회해 전달한다.
     */
    public static ExecutionResult from(RoutineExecution execution, String title, FeedCard card,
                                       boolean backfillable) {
        return new ExecutionResult(
                execution.getId(),
                execution.getRoutineId(),
                title,
                execution.getScheduledDate(),
                execution.getStatus(),
                execution.getCompletedAt(),
                card == null ? null : card.getPhotoUrl(),
                card == null ? null : card.getMemo(),
                card == null ? null : card.getId(),
                backfillable
        );
    }

    /**
     * 저장되지 않은 파생 상태(PENDING/MISSED)로부터 생성한다 — executionId·인증 정보는 없다.
     */
    public static ExecutionResult derived(Long routineId, String title, LocalDate scheduledDate,
                                          ExecutionStatus status, boolean backfillable) {
        return new ExecutionResult(null, routineId, title, scheduledDate, status, null, null, null, null,
                backfillable);
    }
}
