package com.routinely.routine_service.domain.template;

/**
 * 루틴 반복 스케줄 유형 (ADR-0039).
 *
 * <p>두 패러다임으로 나뉜다.
 * <ul>
 *   <li><b>지정형(강제)</b> — {@code DAILY}(매일), {@code SPECIFIC_DAYS}(특정 요일). 지정 요일이 스케줄이며
 *       그 날 미완료는 결석(MISSED)으로 본다.</li>
 *   <li><b>빈도형(유연)</b> — {@code WEEKLY_COUNT}(주 N회), {@code MONTHLY_COUNT}(월 N회). 아무 날이나 수행하며
 *       달성은 완료 수 / N으로 본다.</li>
 * </ul>
 *
 * <p>유형별로 사용하는 컬럼이 다르다: {@code SPECIFIC_DAYS}는 {@code days_of_week}(비트마스크),
 * {@code WEEKLY_COUNT/MONTHLY_COUNT}는 {@code target_count}. routine_templates의
 * {@code ck_rt_schedule} CHECK 제약과 동일한 규칙이다.
 *
 * <p>챌린지 루틴은 멤버 생활 패턴이 달라 특정 요일 강제가 부적절하므로 {@code SPECIFIC_DAYS}를 쓸 수 없다. (ADR-0035)
 */
public enum ScheduleType {
    DAILY,
    SPECIFIC_DAYS,
    WEEKLY_COUNT,
    MONTHLY_COUNT;

    /** 특정 요일 지정(days_of_week)이 필요한 유형인지 여부. */
    public boolean requiresDaysOfWeek() {
        return this == SPECIFIC_DAYS;
    }

    /** 목표 횟수(target_count)가 필요한 유형인지 여부. */
    public boolean requiresTargetCount() {
        return this == WEEKLY_COUNT || this == MONTHLY_COUNT;
    }

    /** 챌린지 루틴에서 허용되는 유형인지 여부 — 특정 요일 지정형은 불가. */
    public boolean allowedForChallenge() {
        return this != SPECIFIC_DAYS;
    }
}
