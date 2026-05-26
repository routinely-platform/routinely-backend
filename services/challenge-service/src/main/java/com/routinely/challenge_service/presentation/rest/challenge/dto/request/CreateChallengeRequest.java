package com.routinely.challenge_service.presentation.rest.challenge.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.routinely.challenge_service.application.dto.CreateChallengeCommand;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.FutureOrPresent;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

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
        Integer maxMembers,

        @NotBlank(message = "카테고리 코드는 필수입니다.")
        @Size(max = 30, message = "카테고리 코드는 30자 이하여야 합니다.")
        String categoryCode,

        @NotNull(message = "시작일은 필수입니다.")
        @FutureOrPresent(message = "시작일은 오늘 이후여야 합니다.")
        LocalDate startedAt,

        @NotNull(message = "종료일은 필수입니다.")
        LocalDate endedAt) {

    @JsonIgnore
    @AssertTrue(message = "종료일은 시작일보다 빠를 수 없습니다.")
    public boolean isDateRangeValid() {
        return startedAt == null || endedAt == null || !endedAt.isBefore(startedAt);
    }

    public CreateChallengeCommand toCommand() {
        return new CreateChallengeCommand(title, description, isPublic, maxMembers, categoryCode, startedAt, endedAt);
    }
}
