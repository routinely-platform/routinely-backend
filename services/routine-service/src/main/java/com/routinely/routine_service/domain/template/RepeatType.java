package com.routinely.routine_service.domain.template;

/**
 * 루틴 반복 유형. challenge-service의 CreateChallengeRequest 검증 정규식
 * {@code ^(DAILY|DAILY_N|WEEKLY|WEEKLY_N|MONTHLY_N)$} 및
 * routine_templates의 {@code ck_rt_repeat_value} CHECK 제약과 동일한 값 집합을 사용한다.
 *
 * <p>{@code DAILY_N / WEEKLY_N / MONTHLY_N} 은 반복 횟수(repeatValue)가 필수이며,
 * {@code DAILY / WEEKLY} 는 repeatValue가 NULL이어야 한다.
 */
public enum RepeatType {
    DAILY, DAILY_N, WEEKLY, WEEKLY_N, MONTHLY_N;

    /**
     * 반복 횟수(repeatValue)가 필수인 유형인지 여부.
     */
    public boolean requiresValue() {
        return this == DAILY_N || this == WEEKLY_N || this == MONTHLY_N;
    }
}
