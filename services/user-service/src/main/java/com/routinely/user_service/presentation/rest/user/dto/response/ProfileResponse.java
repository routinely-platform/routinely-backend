package com.routinely.user_service.presentation.rest.user.dto.response;

import com.routinely.user_service.application.user.dto.ProfileResult;

public record ProfileResponse(Long userId, String email, String nickname, String profileImageUrl) {

    public static ProfileResponse from(ProfileResult result) {
        return new ProfileResponse(result.userId(), result.email(), result.nickname(), result.profileImageUrl());
    }
}
