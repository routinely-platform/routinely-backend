package com.routinely.user_service.application.user.dto;

import com.routinely.user_service.domain.User;

public record ProfileResult(Long userId, String email, String nickname, String profileImageUrl) {

    public static ProfileResult from(User user) {
        return new ProfileResult(user.getId(), user.getEmail(), user.getNickname(), user.getProfileImageUrl());
    }
}
