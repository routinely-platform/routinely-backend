package com.routinely.core.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("BusinessException")
class BusinessExceptionTest {

    @Test
    @DisplayName("생성_ErrorCode만전달_기본메시지사용")
    void constructor_errorCodeOnly_usesDefaultMessage() {
        BusinessException exception = new BusinessException(ErrorCode.USER_NOT_FOUND);

        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.USER_NOT_FOUND);
        assertThat(exception.getMessage()).isEqualTo(ErrorCode.USER_NOT_FOUND.getMessage());
    }

    @Test
    @DisplayName("생성_커스텀메시지전달_커스텀메시지사용")
    void constructor_customMessage_overridesDefault() {
        BusinessException exception =
                new BusinessException(ErrorCode.USER_NOT_FOUND, "user:42 를 찾을 수 없습니다.");

        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.USER_NOT_FOUND);
        assertThat(exception.getMessage()).isEqualTo("user:42 를 찾을 수 없습니다.");
    }

    @Test
    @DisplayName("RuntimeException을상속하여unchecked")
    void isRuntimeException() {
        assertThat(new BusinessException(ErrorCode.FORBIDDEN))
                .isInstanceOf(RuntimeException.class);
    }
}
