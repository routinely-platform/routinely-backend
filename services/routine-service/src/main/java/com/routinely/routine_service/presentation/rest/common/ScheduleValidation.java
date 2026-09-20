package com.routinely.routine_service.presentation.rest.common;

import com.routinely.core.exception.BusinessException;
import com.routinely.routine_service.domain.definition.RoutineDefinition;
import com.routinely.routine_service.domain.template.ScheduleType;
import com.routinely.routine_service.domain.template.Weekdays;
import java.util.List;

/**
 * 반복 스케줄 요청 필드의 유형별 정합성 검증 로직. 생성/수정 요청 DTO가 공유한다.
 * routine_templates의 {@code ck_rt_schedule} CHECK 제약을 요청 계층에서 미러링한다.
 */
public final class ScheduleValidation {

    public static final String TYPE_PATTERN = "^(DAILY|SPECIFIC_DAYS|WEEKLY_COUNT|MONTHLY_COUNT)$";
    public static final String MESSAGE =
            "스케줄 유형과 요일/횟수가 맞지 않습니다. SPECIFIC_DAYS는 요일(daysOfWeek), "
                    + "WEEKLY_COUNT/MONTHLY_COUNT는 목표 횟수(targetCount)가 필요하며, DAILY는 둘 다 없어야 합니다.";

    private ScheduleValidation() {
    }

    /**
     * @param scheduleType 형식 검증(@Pattern)을 통과했다고 가정. 형식 오류/누락이면 true(다른 제약이 처리).
     */
    public static boolean isValid(String scheduleType, List<String> daysOfWeek, Integer targetCount) {
        if (scheduleType == null || !scheduleType.matches(TYPE_PATTERN)) {
            return true;
        }
        try {
            if (daysOfWeek != null && !daysOfWeek.stream().allMatch(Weekdays::isValidCode)) return false;
            RoutineDefinition.of("검증", "검증",
                    ScheduleType.valueOf(scheduleType),
                    daysOfWeek == null || daysOfWeek.isEmpty() ? null : Weekdays.toBitmask(daysOfWeek), targetCount);
            return true;
        } catch (BusinessException ex) {
            return false;
        }
    }
}
