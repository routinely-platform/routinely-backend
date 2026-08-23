package com.routinely.routine_service.domain.execution;

/**
 * 루틴 실행 상태.
 *
 * <p>sparse 저장(ADR-0038)의 원칙은 <b>사용자가 명시적으로 남긴 행동만 저장하고, 시스템이 판단하는 상태는
 * 저장하지 않고 파생한다</b>는 것이다. 현재 사용자가 남기는 행동은 완료뿐이라 저장되는 값도 {@code COMPLETED}
 * 하나이며, {@code PENDING}/{@code MISSED}는 조회 시 스케줄 due 판정(ADR-0039)으로 계산한다.
 * routine_executions의 {@code ck_re_status} CHECK 값 집합과 동일하다.
 *
 * <ul>
 *   <li>{@code PENDING} — 예정(수행 가능하나 아직 미완료). <b>파생</b> 상태.</li>
 *   <li>{@code COMPLETED} — 완료. 사용자가 남긴 행동이라 <b>저장</b>되며 completed_at이 필수({@code ck_re_completed_at}).</li>
 *   <li>{@code MISSED} — 지난 의무일의 미완료. <b>파생</b> 상태이며 확정이 아니다 — 백필로 완료하면 사라진다.</li>
 * </ul>
 *
 * <p>향후 "오늘은 쉬어감"(SKIPPED) 같은 <b>사용자가 선언하는 상태</b>가 생기면 저장 대상에 추가된다.
 * 그때 {@code ck_re_status} CHECK도 함께 넓혀야 한다. 반대로 시스템이 계산하는 값은 계속 저장하지 않는다.
 */
public enum ExecutionStatus {
    PENDING, COMPLETED, MISSED
}
