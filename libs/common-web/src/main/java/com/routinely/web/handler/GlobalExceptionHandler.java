package com.routinely.web.handler;

import com.routinely.core.exception.BusinessException;
import com.routinely.core.exception.ErrorCode;
import com.routinely.core.response.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    // @Valid 검사 실패 시 Spring이 자동으로 던지는 예외 — BindingResult 파라미터 없이 동작
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<?>> handleValidation(MethodArgumentNotValidException e) {
        Map<String, String> errors = new LinkedHashMap<>();
        for (FieldError fieldError : e.getBindingResult().getFieldErrors()) {
            errors.put(fieldError.getField(), fieldError.getDefaultMessage());
        }
        log.warn("Validation failed: {}", errors);
        return ResponseEntity.badRequest()
                .body(ApiResponse.fail(
                        ErrorCode.VALIDATION_FAILED.getCode(),
                        ErrorCode.VALIDATION_FAILED.getMessage(),
                        errors
                ));
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<?>> handleBusiness(BusinessException e) {
        ErrorCode errorCode = e.getErrorCode();
        log.warn("Business exception: code={}, message={}", errorCode.getCode(), e.getMessage());
        return ResponseEntity.status(errorCode.getHttpStatus())
                .body(ApiResponse.fail(errorCode.getCode(), e.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<?>> handleException(Exception e) {
        log.error("Unexpected error", e);
        return ResponseEntity.internalServerError()
                .body(ApiResponse.fail(
                        ErrorCode.INTERNAL_SERVER_ERROR.getCode(),
                        ErrorCode.INTERNAL_SERVER_ERROR.getMessage()
                ));
    }
}
