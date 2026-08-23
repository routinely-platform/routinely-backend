package com.routinely.routine_service.presentation.rest.execution;

import com.routinely.core.exception.BusinessException;
import com.routinely.core.exception.ErrorCode;
import com.routinely.core.response.ApiResponse;
import com.routinely.routine_service.application.execution.RoutineExecutionService;
import com.routinely.routine_service.application.execution.dto.ExecutionCompleteResult;
import com.routinely.routine_service.application.execution.dto.ExecutionResult;
import com.routinely.routine_service.domain.execution.ExecutionStatus;
import com.routinely.routine_service.presentation.rest.execution.dto.response.ExecutionCompleteResponse;
import com.routinely.routine_service.presentation.rest.execution.dto.response.ExecutionResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("RoutineExecutionController")
class RoutineExecutionControllerTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 7, 24);

    private final RoutineExecutionService service = mock(RoutineExecutionService.class);
    private final RoutineExecutionController controller = new RoutineExecutionController(service);

    private static ExecutionCompleteResult completeResult() {
        return new ExecutionCompleteResult(500L, 100L, TODAY, ExecutionStatus.COMPLETED,
                LocalDateTime.of(TODAY, java.time.LocalTime.NOON), "https://cdn/p.jpg");
    }

    @Test
    @DisplayName("완료처리하면_200과완료메시지를반환한다")
    void completeExecution_returnsOk() {
        when(service.complete(any())).thenReturn(completeResult());

        ResponseEntity<ApiResponse<ExecutionCompleteResponse>> response =
                controller.completeExecution(1L, 100L, "2026-07-24", null, "메모");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().getMessage()).isEqualTo("루틴이 완료 처리되었습니다.");
        assertThat(response.getBody().getData().status()).isEqualTo("COMPLETED");
        assertThat(response.getBody().getData().scheduledDate()).isEqualTo(TODAY);
    }

    @Test
    @DisplayName("사진을함께보내면_바이트를커맨드에담아서비스에위임한다")
    void completeExecution_withPhoto_delegates() {
        when(service.complete(any())).thenReturn(completeResult());
        MockMultipartFile photo = new MockMultipartFile(
                "photo", "p.jpg", "image/jpeg", new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF});

        controller.completeExecution(1L, 100L, "2026-07-24", photo, null);

        verify(service).complete(any());
    }

    @Test
    @DisplayName("날짜형식이잘못되면_검증예외를던지고_서비스를호출하지않는다")
    void completeExecution_invalidDate_throws() {
        assertThatThrownBy(() -> controller.completeExecution(1L, 100L, "not-a-date", null, null))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        verify(service, never()).complete(any());
    }

    @Test
    @DisplayName("빈사진파일이면_EMPTY_FILE예외를던진다")
    void completeExecution_emptyPhoto_throws() {
        MockMultipartFile empty = new MockMultipartFile("photo", "p.jpg", "image/jpeg", new byte[0]);

        assertThatThrownBy(() -> controller.completeExecution(1L, 100L, "2026-07-24", empty, null))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.EMPTY_FILE));
        verify(service, never()).complete(any());
    }

    @Test
    @DisplayName("완료취소하면_200과취소메시지를반환한다")
    void cancelExecution_returnsOk() {
        when(service.cancelComplete(100L, 1L, TODAY))
                .thenReturn(ExecutionCompleteResult.cancelled(100L, TODAY));

        ResponseEntity<ApiResponse<ExecutionCompleteResponse>> response =
                controller.cancelExecutionComplete(1L, 100L, "2026-07-24");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().getMessage()).isEqualTo("루틴 완료가 취소되었습니다.");
        assertThat(response.getBody().getData().status()).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("date와startDate를_함께주면_검증예외를던진다")
    void getMyExecutions_dateAndRange_throws() {
        assertThatThrownBy(() -> controller.getMyExecutions(1L, "2026-07-24", "2026-07-01", null, null, null))
                .isInstanceOf(BusinessException.class);
        verify(service, never()).getMyExecutions(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("date도range도없으면_검증예외를던진다")
    void getMyExecutions_neither_throws() {
        assertThatThrownBy(() -> controller.getMyExecutions(1L, null, null, null, null, null))
                .isInstanceOf(BusinessException.class);
        verify(service, never()).getMyExecutions(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("date만주면_start_end를같게해서비스에위임한다")
    void getMyExecutions_singleDate_delegates() {
        when(service.getMyExecutions(eq(1L), isNull(), isNull(), eq(TODAY), eq(TODAY)))
                .thenReturn(List.of(ExecutionResult.derived(100L, "러닝", TODAY, ExecutionStatus.PENDING)));

        ResponseEntity<ApiResponse<List<ExecutionResponse>>> response =
                controller.getMyExecutions(1L, "2026-07-24", null, null, null, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().getData()).hasSize(1);
        verify(service).getMyExecutions(1L, null, null, TODAY, TODAY);
    }

    @Test
    @DisplayName("status가유효하지않으면_검증예외를던진다")
    void getMyExecutions_invalidStatus_throws() {
        assertThatThrownBy(() -> controller.getMyExecutions(1L, "2026-07-24", null, null, null, "DONE"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("조회기간이_366일경계(between=366)를넘으면_검증예외를던진다")
    void getMyExecutions_rangeOverBoundary_throws() {
        // 2026-01-01 ~ 2027-01-02 = between 366 (367개 날짜) → 초과
        assertThatThrownBy(() ->
                controller.getMyExecutions(1L, null, "2026-01-01", "2027-01-02", null, null))
                .isInstanceOf(BusinessException.class);
        verify(service, never()).getMyExecutions(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("조회기간이_정확히366일(between=365)이면_허용해위임한다")
    void getMyExecutions_rangeAtBoundary_delegates() {
        LocalDate start = LocalDate.of(2026, 1, 1);
        LocalDate end = LocalDate.of(2027, 1, 1); // between 365 = 366개 날짜
        when(service.getMyExecutions(eq(1L), isNull(), isNull(), eq(start), eq(end)))
                .thenReturn(List.of());

        controller.getMyExecutions(1L, null, "2026-01-01", "2027-01-01", null, null);

        verify(service).getMyExecutions(1L, null, null, start, end);
    }

    @Test
    @DisplayName("startDate가endDate보다늦으면_검증예외를던진다")
    void getMyExecutions_startAfterEnd_throws() {
        assertThatThrownBy(() ->
                controller.getMyExecutions(1L, null, "2026-07-25", "2026-07-24", null, null))
                .isInstanceOf(BusinessException.class);
    }
}
