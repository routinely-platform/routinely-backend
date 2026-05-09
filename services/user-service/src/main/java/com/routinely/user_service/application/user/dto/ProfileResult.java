package com.routinely.user_service.application.user.dto;

import com.routinely.user_service.domain.User;
import java.time.LocalDateTime;

public record ProfileResult(Long userId, String email, String nickname, String bio, String profileImageUrl, LocalDateTime createdAt) {

    public static ProfileResult from(User user) {
        return new ProfileResult(user.getId(), user.getEmail(), user.getNickname(), user.getBio(), user.getProfileImageUrl(), user.getCreatedAt());
    }
}
