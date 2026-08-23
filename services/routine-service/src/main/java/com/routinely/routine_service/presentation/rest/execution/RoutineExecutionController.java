package com.routinely.routine_service.presentation.rest.execution;

import com.routinely.core.constant.HeaderConstants;
import com.routinely.core.exception.BusinessException;
import com.routinely.core.exception.ErrorCode;
import com.routinely.core.response.ApiResponse;
import com.routinely.routine_service.application.execution.RoutineExecutionService;
import com.routinely.routine_service.application.execution.dto.CompleteExecutionCommand;
import com.routinely.routine_service.application.execution.dto.ExecutionCompleteResult;
import com.routinely.routine_service.domain.execution.ExecutionStatus;
import com.routinely.routine_service.presentation.rest.execution.dto.response.ExecutionCompleteResponse;
import com.routinely.routine_service.presentation.rest.execution.dto.response.ExecutionResponse;
import org.jspecify.annotations.Nullable;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static com.routinely.core.exception.ErrorCode.VALIDATION_FAILED;

/**
 * 루틴 실행 기록 API (#57, sparse 저장).
 *
 * <p>완료/완료 취소는 루틴에 종속된 하루짜리 자원이라 {@code /api/v1/routines/{routineId}/executions}
 * 아래에 두고, 여러 루틴을 가로지르는 조회는 {@code /api/v1/routine-executions}로 분리한다.
 */
@RestController
public class RoutineExecutionController {

    private static final long MAX_QUERY_RANGE_DAYS = 366;

    private final RoutineExecutionService routineExecutionService;

    public RoutineExecutionController(RoutineExecutionService routineExecutionService) {
        this.routineExecutionService = routineExecutionService;
    }

    @PostMapping(value = "/api/v1/routines/{routineId}/executions/{date}/complete",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<ExecutionCompleteResponse>> completeExecution(
            @RequestHeader(HeaderConstants.USER_ID) Long userId,
            @PathVariable Long routineId,
            @PathVariable String date,
            @RequestParam(value = "photo", required = false) @Nullable MultipartFile photo,
            @RequestParam(value = "memo", required = false) @Nullable String memo) {

        LocalDate scheduledDate = parseRequiredDate(date);
        ExecutionCompleteResult result =
                routineExecutionService.complete(toCommand(routineId, userId, scheduledDate, photo, memo));
        return ResponseEntity.ok(
                ApiResponse.ok("루틴이 완료 처리되었습니다.", ExecutionCompleteResponse.from(result)));
    }

    @DeleteMapping("/api/v1/routines/{routineId}/executions/{date}/complete")
    public ResponseEntity<ApiResponse<ExecutionCompleteResponse>> cancelExecutionComplete(
            @RequestHeader(HeaderConstants.USER_ID) Long userId,
            @PathVariable Long routineId,
            @PathVariable String date) {

        LocalDate scheduledDate = parseRequiredDate(date);
        ExecutionCompleteResult result =
                routineExecutionService.cancelComplete(routineId, userId, scheduledDate);
        return ResponseEntity.ok(
                ApiResponse.ok("루틴 완료가 취소되었습니다.", ExecutionCompleteResponse.from(result)));
    }

    @GetMapping("/api/v1/routine-executions")
    public ResponseEntity<ApiResponse<List<ExecutionResponse>>> getMyExecutions(
            @RequestHeader(HeaderConstants.USER_ID) Long userId,
            @RequestParam(required = false) @Nullable String date,
            @RequestParam(required = false) @Nullable String startDate,
            @RequestParam(required = false) @Nullable String endDate,
            @RequestParam(required = false) @Nullable String routineId,
            @RequestParam(required = false) @Nullable String status) {

        if (date != null && (startDate != null || endDate != null)) {
            throw new BusinessException(VALIDATION_FAILED, "date는 startDate/endDate와 함께 사용할 수 없습니다.");
        }

        LocalDate start;
        LocalDate end;
        if (date != null) {
            start = parseDate(date, "date");
            end = start;
        } else {
            start = parseDate(startDate, "startDate");
            end = parseDate(endDate, "endDate");
            if (start == null || end == null) {
                throw new BusinessException(VALIDATION_FAILED, "date 또는 startDate와 endDate가 모두 필요합니다.");
            }
        }
        validateRange(start, end);

        List<ExecutionResponse> response = routineExecutionService.getMyExecutions(
                        userId,
                        parseLong(routineId),
                        parseStatus(status),
                        start,
                        end)
                .stream()
                .map(ExecutionResponse::from)
                .toList();

        return ResponseEntity.ok(ApiResponse.ok("루틴 실행 기록이 조회되었습니다.", response));
    }

    private void validateRange(LocalDate start, LocalDate end) {
        if (start.isAfter(end)) {
            throw new BusinessException(VALIDATION_FAILED, "startDate는 endDate보다 늦을 수 없습니다.");
        }
        // 날짜를 start/end 포함으로 전개하므로 최대 366개 날짜 = between 0~365. between이 366 이상이면 초과.
        if (ChronoUnit.DAYS.between(start, end) >= MAX_QUERY_RANGE_DAYS) {
            throw new BusinessException(VALIDATION_FAILED,
                    "조회 기간은 %d일을 초과할 수 없습니다.".formatted(MAX_QUERY_RANGE_DAYS));
        }
    }

    private CompleteExecutionCommand toCommand(Long routineId, Long userId, LocalDate scheduledDate,
                                               @Nullable MultipartFile photo, @Nullable String memo) {
        if (photo == null) {
            return new CompleteExecutionCommand(routineId, userId, scheduledDate, null, null, null, memo);
        }
        if (photo.isEmpty()) {
            throw new BusinessException(ErrorCode.EMPTY_FILE);
        }
        try {
            return new CompleteExecutionCommand(
                    routineId,
                    userId,
                    scheduledDate,
                    photo.getOriginalFilename(),
                    photo.getContentType(),
                    photo.getBytes(),
                    memo);
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.FILE_UPLOAD_FAILED, "파일을 읽는 중 오류가 발생했습니다.", e);
        }
    }

    private LocalDate parseRequiredDate(String value) {
        LocalDate parsed = parseDate(value, "date");
        if (parsed == null) {
            throw new BusinessException(VALIDATION_FAILED, "date는 필수입니다.");
        }
        return parsed;
    }

    private @Nullable LocalDate parseDate(@Nullable String value, String parameterName) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException e) {
            throw new BusinessException(VALIDATION_FAILED,
                    "%s는 YYYY-MM-DD 형식이어야 합니다.".formatted(parameterName));
        }
    }

    private @Nullable Long parseLong(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            throw new BusinessException(VALIDATION_FAILED, "routineId는 숫자여야 합니다.");
        }
    }

    private @Nullable ExecutionStatus parseStatus(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return ExecutionStatus.valueOf(value.trim());
        } catch (IllegalArgumentException e) {
            String allowed = Arrays.stream(ExecutionStatus.values())
                    .map(Enum::name)
                    .collect(Collectors.joining(", "));
            throw new BusinessException(VALIDATION_FAILED, "status는 %s 중 하나여야 합니다.".formatted(allowed));
        }
    }
}
