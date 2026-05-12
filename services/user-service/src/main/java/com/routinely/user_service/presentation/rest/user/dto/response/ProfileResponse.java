package com.routinely.user_service.presentation.rest.user.dto.response;

import com.routinely.user_service.application.user.dto.ProfileResult;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDateTime;

public record ProfileResponse(
        Long userId,
        String email,
        String nickname,
        String bio,
        String profileImageUrl,
        LocalDateTime createdAt,
        @JsonProperty("nickname_changeable_at")
        LocalDateTime nicknameChangeableAt
) {

    public static ProfileResponse from(ProfileResult result) {
        return new ProfileResponse(
                result.userId(),
                result.email(),
                result.nickname(),
                result.bio(),
                result.profileImageUrl(),
                result.createdAt(),
                result.nicknameChangeableAt()
        );
    }
}
