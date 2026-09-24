package com.routinely.routine_service.domain.schedule;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.WeekFields;

/** 통계와 인정 횟수가 공유하는 한국 시간 기준 일요일 시작 주 경계. */
public final class WeekBoundary {
    public static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    private static final WeekFields WEEKS = WeekFields.of(DayOfWeek.SUNDAY, 1);

    private WeekBoundary() {}

    public static LocalDate startOfWeek(LocalDate date) {
        return date.with(WEEKS.dayOfWeek(), 1);
    }

    public static LocalDate startOfWeek(Instant instant) {
        return startOfWeek(instant.atZone(ZONE).toLocalDate());
    }
}
