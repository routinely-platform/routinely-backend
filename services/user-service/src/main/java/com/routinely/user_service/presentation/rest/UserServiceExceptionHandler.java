package com.routinely.user_service.presentation.rest;

import com.routinely.core.response.ApiResponse;
import com.routinely.user_service.application.user.NicknameCooldownException;
import com.routinely.user_service.application.user.dto.NicknameCooldownData;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
public class UserServiceExceptionHandler {

    @ExceptionHandler(NicknameCooldownException.class)
    public ResponseEntity<ApiResponse<NicknameCooldownData>> handleNicknameCooldown(NicknameCooldownException e) {
        NicknameCooldownData data = new NicknameCooldownData(e.getRemainingDays(), e.getNicknameChangeableAt());
        return ResponseEntity.status(e.getErrorCode().getStatus().getCode())
                .body(ApiResponse.fail(e.getErrorCode().getCode(), e.getMessage(), data));
    }
}
