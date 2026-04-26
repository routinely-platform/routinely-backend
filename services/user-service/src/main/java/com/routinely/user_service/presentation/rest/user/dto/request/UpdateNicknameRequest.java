package com.routinely.user_service.presentation.rest.user.dto.request;

import com.routinely.user_service.application.user.dto.UpdateNicknameCommand;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdateNicknameRequest(
        @NotBlank(message = "닉네임은 필수입니다.")
        @Size(min = 2, max = 20, message = "닉네임은 2~20자여야 합니다.")
        String nickname
) {
    public UpdateNicknameCommand toCommand(Long userId) {
        return new UpdateNicknameCommand(userId, nickname);
    }
}
