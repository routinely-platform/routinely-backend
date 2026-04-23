package com.routinely.user_service.presentation.rest.auth.dto.response;

import com.routinely.user_service.application.auth.dto.UserResult;

public record UserResponse(Long userId, String email, String nickname) {
    public static UserResponse from(UserResult result) {
        return new UserResponse(result.userId(), result.email(), result.nickname());
    }
}
