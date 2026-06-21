package com.routinely.challenge_service.application;

import com.routinely.challenge_service.application.dto.CreateChallengeCommand;
import com.routinely.challenge_service.infrastructure.grpc.RoutineServiceGrpcClient;
import com.routinely.core.exception.BusinessException;
import com.routinely.core.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("ChallengeCreationService")
class ChallengeCreationServiceTest {

    private RoutineServiceGrpcClient routineServiceGrpcClient;
    private ChallengeService challengeService;
    private ChallengeCreationService challengeCreationService;

    @BeforeEach
    void setUp() {
        routineServiceGrpcClient = mock(RoutineServiceGrpcClient.class);
        challengeService = mock(ChallengeService.class);
        challengeCreationService = new ChallengeCreationService(routineServiceGrpcClient, challengeService);
    }

    @Test
    @DisplayName("유효한 카테고리 코드이면 검증을 통과하고 ChallengeService에 생성을 위임한다")
    void createChallenge_whenValidCategory_delegatesToChallengeService() {
        when(routineServiceGrpcClient.getActiveCategoryCodes()).thenReturn(Set.of("EXERCISE", "READING"));
        CreateChallengeCommand command = command("EXERCISE");

        challengeCreationService.createChallenge(100L, command);

        verify(challengeService).createChallengeAfterCategoryValidation(100L, command);
    }

    @Test
    @DisplayName("유효하지 않은 카테고리 코드이면 VALIDATION_FAILED를 던지고 생성을 위임하지 않는다")
    void createChallenge_whenInvalidCategory_throwsAndDoesNotDelegate() {
        when(routineServiceGrpcClient.getActiveCategoryCodes()).thenReturn(Set.of("EXERCISE", "READING"));
        CreateChallengeCommand command = command("INVALID_CODE");

        assertThatThrownBy(() -> challengeCreationService.createChallenge(100L, command))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.VALIDATION_FAILED));

        verify(challengeService, never()).createChallengeAfterCategoryValidation(anyLong(), any());
    }

    @Test
    @DisplayName("카테고리 조회가 실패하면 SERVICE_UNAVAILABLE을 전파하고 생성을 위임하지 않는다 (Fail Closed)")
    void createChallenge_whenCategoryLookupUnavailable_propagatesAndDoesNotDelegate() {
        when(routineServiceGrpcClient.getActiveCategoryCodes())
                .thenThrow(new BusinessException(ErrorCode.SERVICE_UNAVAILABLE));
        CreateChallengeCommand command = command("EXERCISE");

        assertThatThrownBy(() -> challengeCreationService.createChallenge(100L, command))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.SERVICE_UNAVAILABLE));

        verify(challengeService, never()).createChallengeAfterCategoryValidation(anyLong(), any());
    }

    private CreateChallengeCommand command(String categoryCode) {
        return new CreateChallengeCommand(
                "30일 러닝 챌린지",
                "매일 달리기",
                true,
                10,
                categoryCode,
                LocalDate.of(2026, 5, 25),
                LocalDate.of(2026, 6, 24),
                "아침 러닝 30분",
                "DAILY",
                null
        );
    }
}
