package com.routinely.routine_service.presentation.rest.routine.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.routinely.routine_service.application.routine.dto.StartRoutineCommand;
import com.routinely.routine_service.domain.definition.RoutineDefinition;
import com.routinely.routine_service.domain.template.ScheduleType;
import com.routinely.routine_service.domain.template.Weekdays;
import com.routinely.routine_service.presentation.rest.common.ScheduleValidation;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 개인 루틴 시작 요청. 챌린지 루틴은 challenge.started 이벤트로 자동 생성되므로 challengeId를 받지 않는다.
 */
public record StartRoutineRequest(
        Long routineTemplateId,

        @NotNull(message = "시작일은 필수입니다.")
        LocalDate startedAt,

        LocalDate endedAt,

        @Pattern(
                regexp = "^([01]\\d|2[0-3]):[0-5]\\d:[0-5]\\d$",
                message = "선호 수행 시각은 HH:mm:ss 형식이어야 합니다."
        )
        String preferredTime,
        @Size(max = 100, message = "제목은 100자 이하여야 합니다.") String title,
        @Size(max = 30, message = "카테고리는 30자 이하여야 합니다.") String categoryCode,
        @Pattern(regexp = "^(DAILY|SPECIFIC_DAYS|WEEKLY_COUNT|MONTHLY_COUNT)$", message = "반복 유형이 올바르지 않습니다.") String scheduleType,
        List<String> daysOfWeek, Integer targetCount) {

    public StartRoutineRequest(Long templateId, LocalDate start, LocalDate end, String time) {
        this(templateId, start, end, time, null, null, null, null, null);
    }

    @JsonIgnore
    @AssertTrue(message = "템플릿 ID 또는 완전한 루틴 정의 중 하나만 지정해야 합니다.")
    public boolean isSourceExclusive() {
        boolean anyDefinition = title != null || categoryCode != null || scheduleType != null
                || daysOfWeek != null || targetCount != null;
        return routineTemplateId != null ? !anyDefinition
                : title != null && !title.isBlank() && categoryCode != null && !categoryCode.isBlank()
                    && scheduleType != null && !scheduleType.isBlank();
    }

    @JsonIgnore
    @AssertTrue(message = "스케줄 유형과 요일 또는 목표 횟수가 올바르지 않습니다.")
    public boolean isScheduleValid() {
        return ScheduleValidation
                .isValid(scheduleType, daysOfWeek, targetCount);
    }

    @JsonIgnore
    @AssertTrue(message = "시작일은 오늘부터 90일 전까지, 종료일은 시작일 포함 366일 이내여야 합니다.")
    public boolean isPeriodValid() {
        return startedAt == null || (!startedAt.isBefore(LocalDate.now(ZoneId.of("Asia/Seoul")).minusDays(90))
                && (endedAt == null || ChronoUnit.DAYS.between(startedAt, endedAt) < 366));
    }

    @JsonIgnore
    @AssertTrue(message = "종료일은 시작일보다 빠를 수 없습니다.")
    public boolean isDateRangeValid() {
        return startedAt == null || endedAt == null || !endedAt.isBefore(startedAt);
    }

    public StartRoutineCommand toCommand(Long userId) {
        return new StartRoutineCommand(
                userId,
                routineTemplateId,
                startedAt,
                endedAt,
                preferredTime == null ? null : LocalTime.parse(preferredTime),
                routineTemplateId != null ? null : RoutineDefinition.of(
                        title, categoryCode, ScheduleType.valueOf(scheduleType),
                        daysOfWeek == null || daysOfWeek.isEmpty() ? null : Weekdays.toBitmask(daysOfWeek), targetCount)
        );
    }
}
