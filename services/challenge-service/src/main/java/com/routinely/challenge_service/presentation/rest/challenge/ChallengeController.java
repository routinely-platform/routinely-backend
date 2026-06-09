package com.routinely.challenge_service.presentation.rest.challenge;

import com.routinely.challenge_service.application.ChallengeService;
import com.routinely.challenge_service.application.dto.ChallengeListResult;
import com.routinely.challenge_service.application.dto.ChallengeMemberResult;
import com.routinely.challenge_service.application.dto.ChallengeResult;
import com.routinely.challenge_service.domain.challenge.ChallengeLifecycleStatus;
import com.routinely.challenge_service.domain.challenge.ChallengeSearchCondition;
import com.routinely.challenge_service.domain.challenge.ChallengeSort;
import com.routinely.challenge_service.domain.challenge.MyChallengeSearchCondition;
import com.routinely.challenge_service.domain.challenge.MyChallengeSort;
import com.routinely.challenge_service.presentation.rest.challenge.dto.request.CreateChallengeRequest;
import com.routinely.challenge_service.presentation.rest.challenge.dto.request.UpdateChallengeRequest;
import com.routinely.challenge_service.presentation.rest.challenge.dto.response.ChallengeDetailResponse;
import com.routinely.challenge_service.presentation.rest.challenge.dto.response.ChallengeListResponse;
import com.routinely.challenge_service.presentation.rest.challenge.dto.response.ChallengeMemberResponse;
import com.routinely.challenge_service.presentation.rest.challenge.dto.response.ChallengeResponse;
import com.routinely.core.constant.HeaderConstants;
import com.routinely.core.exception.BusinessException;
import com.routinely.core.response.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static com.routinely.core.exception.ErrorCode.VALIDATION_FAILED;
import static org.springframework.http.HttpStatus.CREATED;

@RestController
@RequestMapping("/api/v1/challenges")
public class ChallengeController {

    private final ChallengeService challengeService;

    public ChallengeController(ChallengeService challengeService) {
        this.challengeService = challengeService;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<ChallengeResponse>> createChallenge(
            @RequestHeader(HeaderConstants.USER_ID) Long userId,
            @RequestBody @Valid CreateChallengeRequest request) {

        ChallengeResult result = challengeService.createChallenge(userId, request.toCommand());
        return ResponseEntity.status(CREATED)
                .body(ApiResponse.ok("챌린지가 생성되었습니다.", ChallengeResponse.from(result)));
    }

    @PatchMapping("/{id}")
    public ResponseEntity<ApiResponse<ChallengeResponse>> updateChallenge(
            @RequestHeader(HeaderConstants.USER_ID) Long userId,
            @RequestBody @Valid UpdateChallengeRequest request,
            @PathVariable("id") Long challengeId) {

        ChallengeResult result = challengeService.updateChallenge(challengeId, userId, request.toCommand());
        return ResponseEntity.ok(ApiResponse.ok("챌린지가 수정되었습니다.", ChallengeResponse.from(result)));
    }

    @GetMapping("/me")
    public ResponseEntity<ApiResponse<ChallengeListResponse>> getMyJoinedChallenges(
            @RequestHeader(HeaderConstants.USER_ID) Long userId,
            @RequestParam(defaultValue = "0") String page,
            @RequestParam(defaultValue = "20") String size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) List<String> categoryCode,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String sort) {

        Pageable pageable = createPageable(page, size);
        MyChallengeSearchCondition cond = new MyChallengeSearchCondition(
                keyword,
                categoryCode,
                parseEnum(status, ChallengeLifecycleStatus.class, "status"),
                parseEnum(sort, MyChallengeSort.class, "sort")
        );
        ChallengeListResult result = challengeService.getMyJoinedChallenges(userId, cond, pageable);
        return ResponseEntity.ok(ApiResponse.ok("참여 중인 챌린지 목록이 조회되었습니다.", ChallengeListResponse.from(result)));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<ChallengeListResponse>> getPublicChallenges(
            @RequestHeader(HeaderConstants.USER_ID) Long userId,
            @RequestParam(defaultValue = "0") String page,
            @RequestParam(defaultValue = "20") String size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) List<String> categoryCode,
            @RequestParam(required = false) String sort) {

        Pageable pageable = createPageable(page, size);
        ChallengeSearchCondition cond = new ChallengeSearchCondition(
                keyword,
                categoryCode,
                parseEnum(sort, ChallengeSort.class, "sort")
        );
        ChallengeListResult result = challengeService.getPublicChallenges(userId, cond, pageable);
        return ResponseEntity.ok(ApiResponse.ok("공개 챌린지 목록이 조회되었습니다.", ChallengeListResponse.from(result)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<ChallengeDetailResponse>> getChallengeDetail(
            @RequestHeader(HeaderConstants.USER_ID) Long userId,
            @PathVariable Long id) {

        ChallengeResult result = challengeService.getChallengeDetail(id, userId);
        return ResponseEntity.ok(ApiResponse.ok("챌린지가 조회되었습니다.", ChallengeDetailResponse.from(result)));
    }

    @PostMapping("/{challengeId}/members")
    public ResponseEntity<ApiResponse<ChallengeMemberResponse>> joinChallenge(
            @RequestHeader(HeaderConstants.USER_ID) Long userId,
            @PathVariable Long challengeId) {

        ChallengeMemberResult result = challengeService.joinChallenge(challengeId, userId);
        return ResponseEntity.status(CREATED)
                .body(ApiResponse.ok("챌린지에 참여되었습니다.", ChallengeMemberResponse.from(result)));
    }

    @PostMapping("/{challengeId}/members/me/leave")
    public ResponseEntity<ApiResponse<Void>> leaveChallenge(
            @RequestHeader(HeaderConstants.USER_ID) Long userId,
            @PathVariable Long challengeId) {

        challengeService.leaveChallenge(challengeId, userId);
        return ResponseEntity.ok(ApiResponse.ok("챌린지에서 탈퇴되었습니다.", null));
    }

    private Pageable createPageable(String page, String size) {
        int parsedPage = parseInt(page, "page");
        int parsedSize = parseInt(size, "size");

        if (parsedPage < 0) {
            throw new BusinessException(VALIDATION_FAILED, "page는 0 이상이어야 합니다.");
        }
        if (parsedSize < 1) {
            throw new BusinessException(VALIDATION_FAILED, "size는 1 이상이어야 합니다.");
        }
        return PageRequest.of(parsedPage, parsedSize);
    }

    private int parseInt(String value, String parameterName) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(VALIDATION_FAILED, "%s는 필수입니다.".formatted(parameterName));
        }

        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new BusinessException(VALIDATION_FAILED, "%s는 숫자여야 합니다.".formatted(parameterName));
        }
    }

    private <T extends Enum<T>> T parseEnum(String value, Class<T> enumType, String parameterName) {
        if (value == null || value.isBlank()) {
            return null;
        }

        try {
            return Enum.valueOf(enumType, value.trim());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(
                    VALIDATION_FAILED,
                    "%s는 %s 중 하나여야 합니다.".formatted(parameterName, allowedValues(enumType))
            );
        }
    }

    private <T extends Enum<T>> String allowedValues(Class<T> enumType) {
        return Arrays.stream(enumType.getEnumConstants())
                .map(Enum::name)
                .collect(Collectors.joining(", "));
    }
}
