package com.routinely.routine_service.presentation.rest.routine.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.routinely.routine_service.application.routine.dto.UpdateRoutineCommand;
import com.routinely.routine_service.domain.template.Weekdays;
import com.routinely.routine_service.presentation.rest.common.ScheduleValidation;
import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import lombok.*;

public final class RoutineDto {
    private RoutineDto() {}
    @Getter
    @NoArgsConstructor(access = AccessLevel.PROTECTED)
    public static class UpdateRequest {
        @Size(max = 100, message = "제목은 100자 이하여야 합니다.")
        @Pattern(regexp = "(?s).*\\S.*", message = "제목은 공백일 수 없습니다.")
        private String title;
        @Size(max = 30, message = "카테고리는 30자 이하여야 합니다.")
        @Pattern(regexp = "(?s).*\\S.*", message = "카테고리는 공백일 수 없습니다.")
        private String categoryCode;
        @Pattern(regexp = ScheduleValidation.TYPE_PATTERN, message = "반복 유형이 올바르지 않습니다.")
        private String scheduleType;
        private List<String> daysOfWeek;
        private Integer targetCount;
        private LocalDate startedAt;
        private LocalDate endedAt;
        @Pattern(regexp = "^([01]\\d|2[0-3]):[0-5]\\d:[0-5]\\d$", message = "선호 시각은 HH:mm:ss 형식이어야 합니다.")
        private String preferredTime;
        private List<String> preferredDays;
        private Boolean clearEndedAt;
        private Boolean clearPreferredTime;
        private Boolean clearPreferredDays;

        @JsonIgnore
        @AssertTrue(message = "해제 옵션과 해당 값을 동시에 지정할 수 없습니다.")
        public boolean isClearValid() {
            return !(Boolean.TRUE.equals(clearEndedAt) && endedAt != null)
                && !(Boolean.TRUE.equals(clearPreferredTime) && preferredTime != null)
                && !(Boolean.TRUE.equals(clearPreferredDays) && preferredDays != null);
        }
        @JsonIgnore
        @AssertTrue(message = "선호 요일은 비어 있지 않은 MON~SUN 코드 목록이어야 합니다. 해제는 clearPreferredDays를 사용하세요.")
        public boolean isDaysValid() {
            return preferredDays == null || (!preferredDays.isEmpty() && preferredDays.stream().allMatch(Weekdays::isValidCode));
        }
        @JsonIgnore
        @AssertTrue(message = "주기 수정 시 완전한 스케줄 유형과 요일 또는 목표 횟수를 지정해야 합니다.")
        public boolean isScheduleValid() {
            return scheduleType == null ? daysOfWeek == null && targetCount == null
                    : ScheduleValidation.isValid(scheduleType, daysOfWeek, targetCount);
        }
        @JsonIgnore
        @AssertTrue(message = "시작일은 오늘부터 90일 전까지 허용됩니다.")
        public boolean isStartValid() {
            return startedAt == null || !startedAt.isBefore(LocalDate.now(ZoneId.of("Asia/Seoul")).minusDays(90));
        }
        public UpdateRoutineCommand toCommand() {
            return new UpdateRoutineCommand(title, categoryCode, scheduleType,
                    daysOfWeek == null || daysOfWeek.isEmpty() ? null : Weekdays.toBitmask(daysOfWeek), targetCount,
                    startedAt, endedAt, preferredTime == null ? null : LocalTime.parse(preferredTime),
                    preferredDays == null ? null : Weekdays.toBitmask(preferredDays),
                    Boolean.TRUE.equals(clearEndedAt), Boolean.TRUE.equals(clearPreferredTime), Boolean.TRUE.equals(clearPreferredDays));
        }
    }
}
