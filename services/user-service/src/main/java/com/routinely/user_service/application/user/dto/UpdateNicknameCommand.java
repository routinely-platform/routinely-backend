package com.routinely.user_service.application.user.dto;

public record UpdateNicknameCommand(Long userId, String nickname) {
}
