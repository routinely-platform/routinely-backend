package com.routinely.core.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ErrorStatus")
class ErrorStatusTest {

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource({
            "BAD_REQUEST, 400",
            "UNAUTHORIZED, 401",
            "FORBIDDEN, 403",
            "NOT_FOUND, 404",
            "CONFLICT, 409",
            "INTERNAL_SERVER_ERROR, 500"
    })
    @DisplayName("getCode_각상수_HTTP상태코드반환")
    void getCode_returnsHttpStatusNumber(ErrorStatus status, int expectedCode) {
        assertThat(status.getCode()).isEqualTo(expectedCode);
    }

    @Test
    @DisplayName("values_정의된상수는6개")
    void values_hasSixConstants() {
        assertThat(ErrorStatus.values()).hasSize(6);
    }
}
