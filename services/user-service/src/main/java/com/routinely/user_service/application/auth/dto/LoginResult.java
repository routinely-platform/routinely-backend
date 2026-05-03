package com.routinely.user_service.application.auth.dto;

import com.routinely.user_service.domain.User;

public record LoginResult(String accessToken, String refreshToken, Long userId, String email, String nickname, String profileImageUrl) {

    public static LoginResult of(String accessToken, String refreshToken, User user) {
        return new LoginResult(accessToken, refreshToken, user.getId(), user.getEmail(), user.getNickname(), user.getProfileImageUrl());
    }
}
