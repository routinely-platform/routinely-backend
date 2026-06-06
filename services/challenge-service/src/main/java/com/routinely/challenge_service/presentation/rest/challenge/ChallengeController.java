package com.routinely.challenge_service.presentation.rest.challenge;

import com.routinely.challenge_service.application.ChallengeService;
import com.routinely.challenge_service.application.dto.ChallengeListResult;
import com.routinely.challenge_service.application.dto.ChallengeMemberResult;
import com.routinely.challenge_service.application.dto.ChallengeResult;
import com.routinely.challenge_service.presentation.rest.challenge.dto.request.CreateChallengeRequest;
import com.routinely.challenge_service.presentation.rest.challenge.dto.request.UpdateChallengeRequest;
import com.routinely.challenge_service.presentation.rest.challenge.dto.response.ChallengeDetailResponse;
import com.routinely.challenge_service.presentation.rest.challenge.dto.response.ChallengeListResponse;
import com.routinely.challenge_service.presentation.rest.challenge.dto.response.ChallengeMemberResponse;
import com.routinely.challenge_service.presentation.rest.challenge.dto.response.ChallengeResponse;
import com.routinely.core.constant.HeaderConstants;
import com.routinely.core.response.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "joinedAt"));
        ChallengeListResult result = challengeService.getMyJoinedChallenges(userId, pageable);
        return ResponseEntity.ok(ApiResponse.ok("참여 중인 챌린지 목록이 조회되었습니다.", ChallengeListResponse.from(result)));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<ChallengeListResponse>> getPublicChallenges(
            @RequestHeader(HeaderConstants.USER_ID) Long userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        ChallengeListResult result = challengeService.getPublicChallenges(userId, pageable);
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
}
