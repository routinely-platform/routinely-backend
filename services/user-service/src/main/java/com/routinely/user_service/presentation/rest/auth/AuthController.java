package com.routinely.user_service.presentation.rest.auth;

import com.routinely.core.response.ApiResponse;
import com.routinely.user_service.application.auth.AuthService;
import com.routinely.user_service.application.auth.dto.UserResult;
import com.routinely.user_service.presentation.rest.auth.dto.request.SignUpRequest;
import com.routinely.user_service.presentation.rest.auth.dto.response.UserResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/signup")
    public ResponseEntity<ApiResponse<UserResponse>> signUp(
            @RequestBody @Valid SignUpRequest request) {

        UserResult result = authService.signUp(request.toCommand());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok("회원가입에 성공했습니다.", UserResponse.from(result)));
    }
}
