package com.routinely.user_service.presentation.rest.user;

import com.routinely.core.constant.HeaderConstants;
import com.routinely.core.response.ApiResponse;
import com.routinely.user_service.application.user.UserService;
import com.routinely.user_service.application.user.dto.ProfileResult;
import com.routinely.user_service.presentation.rest.user.dto.request.UpdateNicknameRequest;
import com.routinely.user_service.presentation.rest.user.dto.response.ProfileResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/users")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping("/me")
    public ResponseEntity<ApiResponse<ProfileResponse>> getMyProfile(
            @RequestHeader(HeaderConstants.USER_ID) Long userId) {

        ProfileResult result = userService.getMyProfile(userId);
        return ResponseEntity.ok(ApiResponse.ok("사용자 정보가 조회되었습니다.", ProfileResponse.from(result)));
    }

    @PatchMapping("/me")
    public ResponseEntity<ApiResponse<ProfileResponse>> updateNickname(
            @RequestHeader(HeaderConstants.USER_ID) Long userId,
            @RequestBody @Valid UpdateNicknameRequest request) {

        ProfileResult result = userService.updateNickname(request.toCommand(userId));
        return ResponseEntity.ok(ApiResponse.ok("사용자 정보 변경이 완료되었습니다.", ProfileResponse.from(result)));
    }

    @DeleteMapping("/me")
    public ResponseEntity<ApiResponse<Void>> withdraw(
            @RequestHeader(HeaderConstants.USER_ID) Long userId) {

        userService.withdraw(userId);
        return ResponseEntity.ok(ApiResponse.ok("회원 탈퇴가 완료되었습니다."));
    }
}
