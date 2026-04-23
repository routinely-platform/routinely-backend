package com.routinely.user_service.application.auth.dto;

import com.routinely.user_service.domain.User;

public record UserResult(Long userId, String email, String nickname) {

    public static UserResult from(User user) {
        return new UserResult(user.getId(), user.getEmail(), user.getNickname());
    }
}
