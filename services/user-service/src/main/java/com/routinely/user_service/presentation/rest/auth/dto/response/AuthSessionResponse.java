package com.routinely.user_service.presentation.rest.auth.dto.response;

import com.routinely.user_service.application.auth.dto.LoginResult;

public record AuthSessionResponse(String accessToken, SessionUserResponse user) {

    public static AuthSessionResponse from(LoginResult result) {
        return new AuthSessionResponse(
                result.accessToken(),
                new SessionUserResponse(
                        result.userId(),
                        result.email(),
                        result.nickname(),
                        result.profileImageUrl()
                )
        );
    }

    public record SessionUserResponse(Long userId, String email, String nickname, String profileImageUrl) {}
}
