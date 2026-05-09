package com.routinely.user_service.application.user.dto;

public record UpdateProfileCommand(Long userId, String nickname, String bio) {
}
