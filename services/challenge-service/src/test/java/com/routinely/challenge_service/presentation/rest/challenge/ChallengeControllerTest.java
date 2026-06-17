package com.routinely.challenge_service.presentation.rest.challenge;

import com.routinely.challenge_service.application.ChallengeCreationService;
import com.routinely.challenge_service.application.ChallengeService;
import com.routinely.challenge_service.domain.challenge.ChallengeSearchCondition;
import com.routinely.challenge_service.domain.challenge.MyChallengeSearchCondition;
import com.routinely.core.exception.BusinessException;
import com.routinely.core.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@DisplayName("ChallengeController")
class ChallengeControllerTest {

    private final ChallengeService challengeService = mock(ChallengeService.class);
    private final ChallengeCreationService challengeCreationService = mock(ChallengeCreationService.class);
    private final ChallengeController controller = new ChallengeController(challengeService, challengeCreationService);

    @Test
    @DisplayName("공개챌린지목록조회_sort값이_잘못되면_검증예외를던진다")
    void getPublicChallenges_whenInvalidSort_throwsValidationException() {
        assertThatThrownBy(() -> controller.getPublicChallenges(
                100L,
                "0",
                "20",
                null,
                null,
                "UNKNOWN"
        )).isInstanceOfSatisfying(BusinessException.class, exception -> {
            assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
            assertThat(exception.getMessage()).isEqualTo("sort는 LATEST, IMMINENT, POPULAR 중 하나여야 합니다.");
        });

        verify(challengeService, never()).getPublicChallenges(any(), any(ChallengeSearchCondition.class), any());
    }

    @Test
    @DisplayName("내참여챌린지목록조회_status값이_잘못되면_검증예외를던진다")
    void getMyJoinedChallenges_whenInvalidStatus_throwsValidationException() {
        assertThatThrownBy(() -> controller.getMyJoinedChallenges(
                100L,
                "0",
                "20",
                null,
                null,
                "UNKNOWN",
                null
        )).isInstanceOfSatisfying(BusinessException.class, exception -> {
            assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
            assertThat(exception.getMessage()).isEqualTo("status는 WAITING, ACTIVE, ENDED 중 하나여야 합니다.");
        });

        verify(challengeService, never()).getMyJoinedChallenges(any(), any(MyChallengeSearchCondition.class), any());
    }

    @Test
    @DisplayName("챌린지목록조회_page값이_숫자가아니면_검증예외를던진다")
    void getPublicChallenges_whenPageIsNotNumber_throwsValidationException() {
        assertThatThrownBy(() -> controller.getPublicChallenges(
                100L,
                "abc",
                "20",
                null,
                null,
                null
        )).isInstanceOfSatisfying(BusinessException.class, exception -> {
            assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
            assertThat(exception.getMessage()).isEqualTo("page는 숫자여야 합니다.");
        });

        verify(challengeService, never()).getPublicChallenges(any(), any(ChallengeSearchCondition.class), any());
    }
}
