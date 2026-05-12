package com.routinely.user_service.presentation.rest;

import com.routinely.core.response.ApiResponse;
import com.routinely.user_service.application.user.NicknameCooldownException;
import com.routinely.user_service.presentation.rest.user.dto.response.NicknameCooldownResponseData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("UserServiceExceptionHandler")
class UserServiceExceptionHandlerTest {

    @Test
    @DisplayName("닉네임쿨다운예외_구조화된data와함께409응답")
    void handleNicknameCooldown_returnsConflictWithPayload() {
        UserServiceExceptionHandler handler = new UserServiceExceptionHandler();
        LocalDateTime nicknameChangeableAt = LocalDateTime.of(2026, 5, 29, 9, 0);
        NicknameCooldownException exception = new NicknameCooldownException(20, nicknameChangeableAt);

        ResponseEntity<ApiResponse<NicknameCooldownResponseData>> response = handler.handleNicknameCooldown(exception);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().isSuccess()).isFalse();
        assertThat(response.getBody().getErrorCode()).isEqualTo("NICKNAME_CHANGE_COOLDOWN_ACTIVE");
        assertThat(response.getBody().getMessage()).isEqualTo("닉네임은 아직 변경할 수 없습니다. 20일 후 다시 시도해주세요.");
        assertThat(response.getBody().getData()).isEqualTo(new NicknameCooldownResponseData(20, nicknameChangeableAt));
    }
}
