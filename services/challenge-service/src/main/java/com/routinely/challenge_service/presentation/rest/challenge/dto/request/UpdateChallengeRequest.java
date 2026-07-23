package com.routinely.challenge_service.presentation.rest.challenge.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.routinely.challenge_service.application.dto.UpdateChallengeCommand;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

public record UpdateChallengeRequest(
        @Pattern(regexp = ".*\\S.*", message = "챌린지 이름은 공백만 입력할 수 없습니다.")
        @Size(max = 100, message = "챌린지 이름은 100자 이하여야 합니다.")
        String title,

        @Size(max = 500, message = "챌린지 설명은 500자 이하여야 합니다.")
        String description,

        Boolean isPublic,

        @Min(value = 2, message = "최대 참여 인원은 2명 이상이어야 합니다.")
        Integer maxMembers,

        LocalDate startedAt,
        LocalDate endedAt,

        @Pattern(regexp = ".*\\S.*", message = "루틴 이름은 공백만 입력할 수 없습니다.")
        @Size(max = 100, message = "루틴 이름은 100자 이하여야 합니다.")
        String routineTitle) {

    @JsonIgnore
    @AssertTrue(message = "수정할 챌린지 정보가 하나 이상 필요합니다.")
    public boolean isAnyFieldProvided() {
        return title != null
                || description != null
                || isPublic != null
                || maxMembers != null
                || startedAt != null
                || endedAt != null
                || routineTitle != null;
    }

    @JsonIgnore
    @AssertTrue(message = "종료일은 시작일보다 빠를 수 없습니다.")
    public boolean isDateRangeValid() {
        return startedAt == null || endedAt == null || !endedAt.isBefore(startedAt);
    }

    public UpdateChallengeCommand toCommand() {
        return new UpdateChallengeCommand(
                title,
                description,
                isPublic,
                maxMembers,
                startedAt,
                endedAt,
                routineTitle
        );
    }
}
