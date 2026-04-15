package com.routinely.core.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ErrorCode")
class ErrorCodeTest {

    @Test
    @DisplayName("code_name과동일한값")
    void code_isSameAsEnumName() {
        for (ErrorCode errorCode : ErrorCode.values()) {
            assertThat(errorCode.getCode()).isEqualTo(errorCode.name());
        }
    }

    @Test
    @DisplayName("message_모든상수에대해비어있지않음")
    void message_isNeverBlank() {
        for (ErrorCode errorCode : ErrorCode.values()) {
            assertThat(errorCode.getMessage()).isNotBlank();
        }
    }

    @Test
    @DisplayName("status_모든상수에대해할당됨")
    void status_isNeverNull() {
        for (ErrorCode errorCode : ErrorCode.values()) {
            assertThat(errorCode.getStatus()).isNotNull();
        }
    }

    @Test
    @DisplayName("USER_NOT_FOUND_NOT_FOUND상태매핑")
    void userNotFound_mapsToNotFound() {
        assertThat(ErrorCode.USER_NOT_FOUND.getStatus()).isEqualTo(ErrorStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("CHALLENGE_ALREADY_JOINED_CONFLICT상태매핑")
    void challengeAlreadyJoined_mapsToConflict() {
        assertThat(ErrorCode.CHALLENGE_ALREADY_JOINED.getStatus()).isEqualTo(ErrorStatus.CONFLICT);
    }

    @Test
    @DisplayName("NOT_CHALLENGE_MEMBER_FORBIDDEN상태매핑")
    void notChallengeMember_mapsToForbidden() {
        assertThat(ErrorCode.NOT_CHALLENGE_MEMBER.getStatus()).isEqualTo(ErrorStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("VALIDATION_FAILED_BAD_REQUEST상태매핑")
    void validationFailed_mapsToBadRequest() {
        assertThat(ErrorCode.VALIDATION_FAILED.getStatus()).isEqualTo(ErrorStatus.BAD_REQUEST);
    }
}
