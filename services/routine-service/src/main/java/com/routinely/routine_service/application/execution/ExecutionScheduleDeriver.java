package com.routinely.routine_service.application.execution;

import com.routinely.routine_service.domain.execution.ExecutionStatus;
import com.routinely.routine_service.domain.definition.RoutineDefinition;
import com.routinely.routine_service.domain.template.Weekdays;

import java.time.LocalDate;
import java.util.Optional;

/**
 * sparse 실행 상태 파생 엔진 — 저장되지 않은 미완료일의 상태(PENDING/MISSED)를 루틴 스케줄 유형으로
 * 계산한다. (ADR-0038 sparse 저장, ADR-0039 반복 스케줄 모델)
 *
 * <p>두 개념을 구분한다.
 * <ul>
 *   <li><b>수행 가능(completable)</b> — 그 날 완료 처리를 허용하는가. 지정형(SPECIFIC_DAYS)은 지정 요일만,
 *       빈도형·DAILY은 아무 날. 완료 API 검증에도 쓴다.</li>
 *   <li><b>의무(obligation)</b> — 미완료 시 결석(MISSED)/예정(PENDING)으로 표시되는 스케줄 의무일인가.
 *       지정형·DAILY만 의무이고, 빈도형은 per-day 의무가 없다(달성 = 완료 수/N, 특정일 MISSED 없음).</li>
 * </ul>
 *
 * <p><b>{@link RoutineDefinition}만 받는다</b> — 템플릿도 루틴도 같은 정의를 갖기 때문이다(ADR-0040).
 * 전에는 템플릿 엔티티를 받아 파생할 때마다 조회해야 했고, 그래서 템플릿을 고치면 과거 판정이
 * 소급되는 문제가 있었다.
 */
public final class ExecutionScheduleDeriver {

    private ExecutionScheduleDeriver() {
    }

    /**
     * 해당 날짜에 완료 처리가 가능한가.
     */
    public static boolean isCompletable(RoutineDefinition definition, LocalDate date) {
        return switch (definition.getScheduleType()) {
            case DAILY, WEEKLY_COUNT, MONTHLY_COUNT -> true;
            case SPECIFIC_DAYS -> Weekdays.contains(definition.getDaysOfWeek(), date.getDayOfWeek());
        };
    }

    private static boolean isObligation(RoutineDefinition definition, LocalDate date) {
        return switch (definition.getScheduleType()) {
            case DAILY -> true;
            case SPECIFIC_DAYS -> Weekdays.contains(definition.getDaysOfWeek(), date.getDayOfWeek());
            case WEEKLY_COUNT, MONTHLY_COUNT -> false;
        };
    }

    /**
     * 완료 기록이 없는 날짜의 파생 상태를 계산한다. 표시 대상이 아니면(지정형 비수행 요일, 빈도형의 지난
     * 미완료일 등) 빈 값을 반환한다.
     *
     * <ul>
     *   <li>오늘: 수행 가능하면 PENDING(수행하라고 표시), 아니면 표시 안 함</li>
     *   <li>과거: 지정형 의무일이면 MISSED, 아니면 표시 안 함(빈도형 지난 미완료일은 결석이 아님)</li>
     *   <li>미래: 지정형 의무일이면 PENDING(예정), 아니면 표시 안 함(빈도형 미래일은 진행률로만 표시)</li>
     * </ul>
     */
    public static Optional<ExecutionStatus> deriveStatus(RoutineDefinition definition, LocalDate date, LocalDate today) {
        if (date.isEqual(today)) {
            return isCompletable(definition, date) ? Optional.of(ExecutionStatus.PENDING) : Optional.empty();
        }
        if (date.isBefore(today)) {
            return isObligation(definition, date) ? Optional.of(ExecutionStatus.MISSED) : Optional.empty();
        }
        return isObligation(definition, date) ? Optional.of(ExecutionStatus.PENDING) : Optional.empty();
    }
}
