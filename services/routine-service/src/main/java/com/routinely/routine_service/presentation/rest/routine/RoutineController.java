package com.routinely.routine_service.presentation.rest.routine;

import com.routinely.core.constant.HeaderConstants;
import com.routinely.core.exception.BusinessException;
import com.routinely.core.response.ApiResponse;
import com.routinely.routine_service.application.routine.RoutineService;
import com.routinely.routine_service.application.routine.dto.RoutineResult;
import com.routinely.routine_service.presentation.rest.routine.dto.request.StartRoutineRequest;
import com.routinely.routine_service.presentation.rest.routine.dto.response.RoutineResponse;
import jakarta.validation.Valid;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static com.routinely.core.exception.ErrorCode.VALIDATION_FAILED;
import static org.springframework.http.HttpStatus.CREATED;

@RestController
@RequestMapping("/api/v1/routines")
public class RoutineController {

    private final RoutineService routineService;

    public RoutineController(RoutineService routineService) {
        this.routineService = routineService;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<RoutineResponse>> startRoutine(
            @RequestHeader(HeaderConstants.USER_ID) Long userId,
            @RequestBody @Valid StartRoutineRequest request) {

        RoutineResult result = routineService.start(request.toCommand(userId));
        return ResponseEntity.status(CREATED)
                .body(ApiResponse.ok("루틴이 시작되었습니다.", RoutineResponse.from(result)));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<RoutineResponse>>> getMyRoutines(
            @RequestHeader(HeaderConstants.USER_ID) Long userId,
            @RequestParam(required = false) @Nullable String isActive,
            @RequestParam(required = false) @Nullable String challengeId) {

        List<RoutineResponse> response = routineService.getMyRoutines(
                        userId, parseBoolean(isActive), parseLong(challengeId, "challengeId"))
                .stream()
                .map(RoutineResponse::from)
                .toList();

        return ResponseEntity.ok(ApiResponse.ok("루틴 목록이 조회되었습니다.", response));
    }

    @DeleteMapping("/{routineId}")
    public ResponseEntity<ApiResponse<Void>> stopRoutine(
            @RequestHeader(HeaderConstants.USER_ID) Long userId,
            @PathVariable Long routineId) {

        routineService.stop(routineId, userId);
        return ResponseEntity.ok(ApiResponse.ok("루틴이 중단되었습니다.", null));
    }

    private @Nullable Boolean parseBoolean(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.equalsIgnoreCase("true")) {
            return Boolean.TRUE;
        }
        if (trimmed.equalsIgnoreCase("false")) {
            return Boolean.FALSE;
        }
        throw new BusinessException(VALIDATION_FAILED, "isActive는 true 또는 false여야 합니다.");
    }

    private @Nullable Long parseLong(@Nullable String value, String parameterName) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            throw new BusinessException(VALIDATION_FAILED, "%s는 숫자여야 합니다.".formatted(parameterName));
        }
    }
}
