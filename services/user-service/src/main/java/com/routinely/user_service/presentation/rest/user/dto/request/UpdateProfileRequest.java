package com.routinely.user_service.presentation.rest.user.dto.request;

import com.routinely.user_service.application.user.dto.UpdateProfileCommand;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdateProfileRequest(
        @NotBlank(message = "닉네임은 필수입니다.")
        @Size(min = 2, max = 20, message = "닉네임은 2~20자여야 합니다.")
        String nickname,

        @Size(max = 100, message = "한 줄 소개는 100자 이하여야 합니다.")
        String bio
) {
    public UpdateProfileCommand toCommand(Long userId) {
        return new UpdateProfileCommand(userId, nickname, bio);
    }
}
