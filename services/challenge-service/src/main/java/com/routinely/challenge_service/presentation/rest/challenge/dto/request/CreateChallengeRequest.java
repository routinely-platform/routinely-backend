package com.routinely.challenge_service.presentation.rest.challenge.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.routinely.challenge_service.application.dto.CreateChallengeCommand;
import com.routinely.challenge_service.domain.challenge.ChallengePolicy;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.FutureOrPresent;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
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

        // 챌린지 루틴은 특정 요일 지정(SPECIFIC_DAYS)이 불가하다 — 빈도 유형만 허용 (ADR-0039, ADR-0035).
        @NotBlank(message = "반복 유형은 필수입니다.")
        @Pattern(regexp = "^(DAILY|WEEKLY_COUNT|MONTHLY_COUNT)$", message = "올바르지 않은 반복 유형입니다.")
        String scheduleType,

        @Min(value = 1, message = "목표 횟수는 1 이상이어야 합니다.")
        Integer targetCount) {

    // target_count는 WEEKLY_COUNT/MONTHLY_COUNT에서만 의미가 있다 (routine_templates.ck_rt_schedule 미러링).
    private static final Set<String> COUNT_REQUIRED_TYPES = Set.of("WEEKLY_COUNT", "MONTHLY_COUNT");

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

    public CreateChallengeCommand toCommand() {
        return new CreateChallengeCommand(
                title, description, isPublic, maxMembers, categoryCode, startedAt, endedAt,
                routineTitle, scheduleType, targetCount);
    }
}
