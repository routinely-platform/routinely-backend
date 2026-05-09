package com.routinely.user_service.presentation.rest.user.dto.response;

import com.routinely.user_service.application.user.dto.ProfileResult;

import java.time.LocalDateTime;

public record ProfileResponse(Long userId, String email, String nickname, String bio, String profileImageUrl, LocalDateTime createdAt) {

    public static ProfileResponse from(ProfileResult result) {
        return new ProfileResponse(result.userId(), result.email(), result.nickname(), result.bio(), result.profileImageUrl(), result.createdAt());
    }
}
