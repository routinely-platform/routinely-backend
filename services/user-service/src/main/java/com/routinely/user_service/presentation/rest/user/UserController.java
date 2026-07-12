package com.routinely.user_service.presentation.rest.user;

import com.routinely.core.constant.HeaderConstants;
import com.routinely.core.exception.BusinessException;
import com.routinely.core.exception.ErrorCode;
import com.routinely.core.response.ApiResponse;
import com.routinely.user_service.application.user.ProfileImageService;
import com.routinely.user_service.application.user.UserService;
import com.routinely.user_service.application.user.dto.ProfileImageUploadCommand;
import com.routinely.user_service.application.user.dto.ProfileResult;
import com.routinely.user_service.presentation.rest.user.dto.request.UpdateProfileRequest;
import com.routinely.user_service.presentation.rest.user.dto.response.ProfileResponse;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

@RestController
@RequestMapping("/api/v1/users")
public class UserController {

    private final UserService userService;
    private final ProfileImageService profileImageService;

    public UserController(UserService userService, ProfileImageService profileImageService) {
        this.userService = userService;
        this.profileImageService = profileImageService;
    }

    @GetMapping("/me")
    public ResponseEntity<ApiResponse<ProfileResponse>> getMyProfile(
            @RequestHeader(HeaderConstants.USER_ID) Long userId) {

        ProfileResult result = userService.getMyProfile(userId);
        return ResponseEntity.ok(ApiResponse.ok("사용자 정보가 조회되었습니다.", ProfileResponse.from(result)));
    }

    @PatchMapping("/me")
    public ResponseEntity<ApiResponse<ProfileResponse>> updateProfile(
            @RequestHeader(HeaderConstants.USER_ID) Long userId,
            @RequestBody @Valid UpdateProfileRequest request) {

        ProfileResult result = userService.updateProfile(request.toCommand(userId));
        return ResponseEntity.ok(ApiResponse.ok("사용자 정보 변경이 완료되었습니다.", ProfileResponse.from(result)));
    }

    @DeleteMapping("/me")
    public ResponseEntity<ApiResponse<Void>> withdraw(
            @RequestHeader(HeaderConstants.USER_ID) Long userId) {

        userService.withdraw(userId);
        return ResponseEntity.ok(ApiResponse.ok("회원 탈퇴가 완료되었습니다."));
    }

    @PutMapping(value = "/me/profile-image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<ProfileResponse>> uploadProfileImage(
            @RequestHeader(HeaderConstants.USER_ID) Long userId,
            @RequestParam(value = "image", required = false) MultipartFile image) {

        profileImageService.upload(toUploadCommand(userId, image));
        ProfileResult result = userService.getMyProfile(userId);
        return ResponseEntity.ok(ApiResponse.ok("프로필 이미지가 변경되었습니다.", ProfileResponse.from(result)));
    }

    @DeleteMapping("/me/profile-image")
    public ResponseEntity<ApiResponse<ProfileResponse>> deleteProfileImage(
            @RequestHeader(HeaderConstants.USER_ID) Long userId) {

        profileImageService.delete(userId);
        ProfileResult result = userService.getMyProfile(userId);
        return ResponseEntity.ok(ApiResponse.ok("프로필 이미지가 삭제되었습니다.", ProfileResponse.from(result)));
    }

    private ProfileImageUploadCommand toUploadCommand(Long userId, MultipartFile image) {
        if (image == null || image.isEmpty()) {
            throw new BusinessException(ErrorCode.EMPTY_FILE);
        }
        try {
            return new ProfileImageUploadCommand(
                    userId,
                    image.getOriginalFilename(),
                    image.getContentType(),
                    image.getBytes());
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.FILE_UPLOAD_FAILED, "파일을 읽는 중 오류가 발생했습니다.", e);
        }
    }
}
