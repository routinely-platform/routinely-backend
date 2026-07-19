package com.routinely.routine_service.presentation.rest.routine;

import com.routinely.core.exception.BusinessException;
import com.routinely.core.exception.ErrorCode;
import com.routinely.core.response.ApiResponse;
import com.routinely.routine_service.application.routine.RoutineService;
import com.routinely.routine_service.application.routine.dto.RoutineResult;
import com.routinely.routine_service.presentation.rest.routine.dto.request.StartRoutineRequest;
import com.routinely.routine_service.presentation.rest.routine.dto.response.RoutineResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("RoutineController")
class RoutineControllerTest {

    private final RoutineService routineService = mock(RoutineService.class);
    private final RoutineController controller = new RoutineController(routineService);

    private static final RoutineResult RESULT = new RoutineResult(
            100L, 10L, "아침 러닝 30분", null,
            LocalDate.of(2026, 2, 1), LocalDate.of(2026, 3, 2), LocalTime.of(7, 0), true);

    @Test
    @DisplayName("루틴시작하면_201과시작메시지를반환한다")
    void startRoutine_returns201WithBody() {
        when(routineService.start(any())).thenReturn(RESULT);

        ResponseEntity<ApiResponse<RoutineResponse>> response = controller.startRoutine(
                1L, new StartRoutineRequest(10L, LocalDate.of(2026, 2, 1), LocalDate.of(2026, 3, 2), "07:00:00"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().getMessage()).isEqualTo("루틴이 시작되었습니다.");
        assertThat(response.getBody().getData().routineId()).isEqualTo(100L);
        assertThat(response.getBody().getData().preferredTime()).isEqualTo("07:00:00");
    }

    @Test
    @DisplayName("목록조회는_필터를파싱해서비스에전달한다")
    void getMyRoutines_parsesFiltersAndDelegates() {
        when(routineService.getMyRoutines(1L, Boolean.TRUE, 42L)).thenReturn(List.of(RESULT));

        ResponseEntity<ApiResponse<List<RoutineResponse>>> response =
                controller.getMyRoutines(1L, "true", "42");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().getMessage()).isEqualTo("루틴 목록이 조회되었습니다.");
        assertThat(response.getBody().getData()).hasSize(1);
        verify(routineService).getMyRoutines(1L, Boolean.TRUE, 42L);
    }

    @Test
    @DisplayName("필터가없으면_null로서비스에전달한다")
    void getMyRoutines_whenNoFilter_passesNull() {
        when(routineService.getMyRoutines(1L, null, null)).thenReturn(List.of());

        controller.getMyRoutines(1L, null, null);

        verify(routineService).getMyRoutines(1L, null, null);
    }

    @Test
    @DisplayName("isActive가true_false가아니면_검증예외를던지고서비스를호출하지않는다")
    void getMyRoutines_whenInvalidIsActive_throwsValidationException() {
        assertThatThrownBy(() -> controller.getMyRoutines(1L, "yes", null))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(exception.getMessage()).isEqualTo("isActive는 true 또는 false여야 합니다.");
                });

        verify(routineService, never()).getMyRoutines(any(), any(), any());
    }

    @Test
    @DisplayName("challengeId가숫자가아니면_검증예외를던지고서비스를호출하지않는다")
    void getMyRoutines_whenInvalidChallengeId_throwsValidationException() {
        assertThatThrownBy(() -> controller.getMyRoutines(1L, null, "abc"))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(exception.getMessage()).isEqualTo("challengeId는 숫자여야 합니다.");
                });

        verify(routineService, never()).getMyRoutines(any(), any(), any());
    }

    @Test
    @DisplayName("루틴중단하면_서비스에위임하고중단메시지를반환한다")
    void stopRoutine_delegatesToService() {
        ResponseEntity<ApiResponse<Void>> response = controller.stopRoutine(1L, 100L);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().getMessage()).isEqualTo("루틴이 중단되었습니다.");
        verify(routineService).stop(100L, 1L);
    }
}
