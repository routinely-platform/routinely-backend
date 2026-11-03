package com.routinely.challenge_service.presentation.rest.challenge.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.routinely.challenge_service.application.dto.CreateChallengeCommand;
import com.routinely.challenge_service.domain.challenge.ChallengePolicy;
import com.routinely.core.schedule.Weekdays;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.FutureOrPresent;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

public record CreateChallengeRequest(
        @NotBlank(message = "챌린지 이름은 필수입니다.")
        @Size(max = 100, message = "챌린지 이름은 100자 이하여야 합니다.")
        String title,

        @Size(max = 500, message = "챌린지 설명은 500자 이하여야 합니다.")
        String description,

        @NotNull(message = "공개 여부는 필수입니다.")
        Boolean isPublic,

        @NotNull(message = "최대 참여 인원은 필수입니다.")
        @Min(value = 2, message = "최대 참여 인원은 2명 이상이어야 합니다.")
        @Max(value = ChallengePolicy.MAX_MEMBERS, message = ChallengePolicy.MAX_MEMBERS_MESSAGE)
        Integer maxMembers,

        @NotBlank(message = "카테고리 코드는 필수입니다.")
        @Size(max = 30, message = "카테고리 코드는 30자 이하여야 합니다.")
        String categoryCode,

        @NotNull(message = "시작일은 필수입니다.")
        @FutureOrPresent(message = "시작일은 오늘 이후여야 합니다.")
        LocalDate startedAt,

        @NotNull(message = "종료일은 필수입니다.")
        LocalDate endedAt,

        @NotBlank(message = "루틴 이름은 필수입니다.")
        @Size(max = 100, message = "루틴 이름은 100자 이하여야 합니다.")
        String routineTitle,

        // 시작 전 참여가 사전 동의이므로 챌린지 루틴도 요일 지정(SPECIFIC_DAYS)을 쓸 수 있다 (ADR-0039 §2.3, 2026-08-29 개정).
        @NotBlank(message = "반복 유형은 필수입니다.")
        @Pattern(regexp = "^(DAILY|SPECIFIC_DAYS|WEEKLY_COUNT|MONTHLY_COUNT)$", message = "올바르지 않은 반복 유형입니다.")
        String scheduleType,

        // 요일 코드 리스트(MON~SUN). SPECIFIC_DAYS 전용, 그 외 null. 저장은 비트마스크로 한다.
        List<String> daysOfWeek,

        @Min(value = 1, message = "목표 횟수는 1 이상이어야 합니다.")
        Integer targetCount) {

    // 아래 세 검증이 challenges.ck_challenges_schedule을 요청 계층에서 미러링한다.
    // target_count는 WEEKLY_COUNT/MONTHLY_COUNT에서만, days_of_week는 SPECIFIC_DAYS에서만 의미가 있다.
    private static final Set<String> COUNT_REQUIRED_TYPES = Set.of("WEEKLY_COUNT", "MONTHLY_COUNT");
    private static final String DAYS_REQUIRED_TYPE = "SPECIFIC_DAYS";
    private static final int MAX_WEEKLY_TARGET_COUNT = 6;
    private static final int MAX_MONTHLY_TARGET_COUNT = 28;

    @JsonIgnore
    @AssertTrue(message = "MVP에서는 공개 챌린지만 만들 수 있습니다.")
    public boolean isPublicChallenge() {
        return isPublic == null || isPublic;
    }

    @JsonIgnore
    @AssertTrue(message = "종료일은 시작일보다 빠를 수 없습니다.")
    public boolean isDateRangeValid() {
        return startedAt == null || endedAt == null || !endedAt.isBefore(startedAt);
    }

    @JsonIgnore
    @AssertTrue(message = ChallengePolicy.MAX_PERIOD_MESSAGE)
    public boolean isPeriodWithinLimit() {
        return startedAt == null
                || endedAt == null
                || ChallengePolicy.isPeriodWithinLimit(startedAt, endedAt);
    }

    @JsonIgnore
    @AssertTrue(message = "목표 횟수는 WEEKLY_COUNT/MONTHLY_COUNT에서만 지정할 수 있으며, 해당 유형에서는 필수입니다.")
    public boolean isTargetCountValid() {
        if (scheduleType == null) {
            return true; // scheduleType 누락은 @NotBlank가 처리한다.
        }
        boolean countRequired = COUNT_REQUIRED_TYPES.contains(scheduleType);
        return countRequired ? targetCount != null : targetCount == null;
    }

    @JsonIgnore
    @AssertTrue(message = "목표 횟수는 WEEKLY_COUNT는 6회, MONTHLY_COUNT는 28회 이하여야 합니다.")
    public boolean isTargetCountWithinLimit() {
        if (scheduleType == null || targetCount == null) {
            return true;
        }
        return switch (scheduleType) {
            case "WEEKLY_COUNT" -> targetCount <= MAX_WEEKLY_TARGET_COUNT;
            case "MONTHLY_COUNT" -> targetCount <= MAX_MONTHLY_TARGET_COUNT;
            default -> true; // 다른 유형의 targetCount는 isTargetCountValid가 거부한다.
        };
    }

    @JsonIgnore
    @AssertTrue(message = "요일은 SPECIFIC_DAYS에서만 지정할 수 있으며, 해당 유형에서는 MON~SUN 중 하나 이상이 필요합니다.")
    public boolean isDaysOfWeekValid() {
        if (scheduleType == null) {
            return true; // scheduleType 누락은 @NotBlank가 처리한다.
        }
        if (!DAYS_REQUIRED_TYPE.equals(scheduleType)) {
            return daysOfWeek == null;
        }
        return daysOfWeek != null
                && !daysOfWeek.isEmpty()
                && daysOfWeek.stream().allMatch(Weekdays::isValidCode);
    }

    public CreateChallengeCommand toCommand() {
        return new CreateChallengeCommand(
                title, description, isPublic, maxMembers, categoryCode, startedAt, endedAt,
                routineTitle, scheduleType,
                daysOfWeek == null ? null : Weekdays.toBitmask(daysOfWeek),
                targetCount);
    }
}
