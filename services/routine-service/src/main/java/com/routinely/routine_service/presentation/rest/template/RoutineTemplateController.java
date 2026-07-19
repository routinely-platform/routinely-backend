package com.routinely.routine_service.presentation.rest.template;

import com.routinely.core.constant.HeaderConstants;
import com.routinely.core.response.ApiResponse;
import com.routinely.routine_service.application.template.RoutineTemplateService;
import com.routinely.routine_service.application.template.dto.RoutineTemplateResult;
import com.routinely.routine_service.presentation.rest.template.dto.request.CreateRoutineTemplateRequest;
import com.routinely.routine_service.presentation.rest.template.dto.request.UpdateRoutineTemplateRequest;
import com.routinely.routine_service.presentation.rest.template.dto.response.RoutineTemplateResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static org.springframework.http.HttpStatus.CREATED;

@RestController
@RequestMapping("/api/v1/routine-templates")
public class RoutineTemplateController {

    private final RoutineTemplateService routineTemplateService;

    public RoutineTemplateController(RoutineTemplateService routineTemplateService) {
        this.routineTemplateService = routineTemplateService;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<RoutineTemplateResponse>> createTemplate(
            @RequestHeader(HeaderConstants.USER_ID) Long userId,
            @RequestBody @Valid CreateRoutineTemplateRequest request) {

        RoutineTemplateResult result = routineTemplateService.create(request.toCommand(userId));
        return ResponseEntity.status(CREATED)
                .body(ApiResponse.ok("루틴 템플릿이 생성되었습니다.", RoutineTemplateResponse.from(result)));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<RoutineTemplateResponse>>> getMyTemplates(
            @RequestHeader(HeaderConstants.USER_ID) Long userId,
            @RequestParam(required = false) String categoryCode) {

        List<RoutineTemplateResponse> response = routineTemplateService.getMyTemplates(userId, categoryCode)
                .stream()
                .map(RoutineTemplateResponse::from)
                .toList();

        return ResponseEntity.ok(ApiResponse.ok("루틴 템플릿 목록이 조회되었습니다.", response));
    }

    @GetMapping("/{templateId}")
    public ResponseEntity<ApiResponse<RoutineTemplateResponse>> getTemplate(
            @RequestHeader(HeaderConstants.USER_ID) Long userId,
            @PathVariable Long templateId) {

        RoutineTemplateResult result = routineTemplateService.getTemplate(templateId, userId);
        return ResponseEntity.ok(ApiResponse.ok("루틴 템플릿이 조회되었습니다.", RoutineTemplateResponse.from(result)));
    }

    @PatchMapping("/{templateId}")
    public ResponseEntity<ApiResponse<RoutineTemplateResponse>> updateTemplate(
            @RequestHeader(HeaderConstants.USER_ID) Long userId,
            @PathVariable Long templateId,
            @RequestBody @Valid UpdateRoutineTemplateRequest request) {

        RoutineTemplateResult result = routineTemplateService.update(request.toCommand(templateId, userId));
        return ResponseEntity.ok(ApiResponse.ok("루틴 템플릿이 수정되었습니다.", RoutineTemplateResponse.from(result)));
    }

    @DeleteMapping("/{templateId}")
    public ResponseEntity<ApiResponse<Void>> deleteTemplate(
            @RequestHeader(HeaderConstants.USER_ID) Long userId,
            @PathVariable Long templateId) {

        routineTemplateService.delete(templateId, userId);
        return ResponseEntity.ok(ApiResponse.ok("루틴 템플릿이 삭제되었습니다.", null));
    }
}
