package com.routinely.user_service.presentation.rest.auth;

import com.routinely.core.exception.BusinessException;
import com.routinely.core.exception.ErrorCode;
import com.routinely.core.response.ApiResponse;
import com.routinely.user_service.application.auth.AuthService;
import com.routinely.user_service.application.auth.dto.LoginResult;
import com.routinely.user_service.application.auth.dto.UserResult;
import com.routinely.user_service.infrastructure.config.JwtProperties;
import com.routinely.user_service.presentation.rest.auth.dto.request.LoginRequest;
import com.routinely.user_service.presentation.rest.auth.dto.request.SignUpRequest;
import com.routinely.user_service.presentation.rest.auth.dto.response.LoginResponse;
import com.routinely.user_service.presentation.rest.auth.dto.response.UserResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private static final String REFRESH_TOKEN_COOKIE = "refresh_token";

    private final AuthService authService;
    private final JwtProperties jwtProperties;

    public AuthController(AuthService authService, JwtProperties jwtProperties) {
        this.authService = authService;
        this.jwtProperties = jwtProperties;
    }

    @PostMapping("/signup")
    public ResponseEntity<ApiResponse<UserResponse>> signUp(
            @RequestBody @Valid SignUpRequest request) {

        UserResult result = authService.signUp(request.toCommand());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok("회원가입에 성공했습니다.", UserResponse.from(result)));
    }

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<LoginResponse>> login(
            @RequestBody @Valid LoginRequest request) {

        LoginResult result = authService.login(request.toCommand());
        ResponseCookie cookie = buildRefreshCookie(result.refreshToken(), jwtProperties.refreshExpirationSeconds());
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .body(ApiResponse.ok("로그인에 성공했습니다.", new LoginResponse(result.accessToken())));
    }

    @PostMapping("/refresh")
    public ResponseEntity<ApiResponse<LoginResponse>> refresh(
            @CookieValue(name = REFRESH_TOKEN_COOKIE, required = false) String refreshToken) {

        if (refreshToken == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }

        LoginResult result = authService.refresh(refreshToken);
        ResponseCookie cookie = buildRefreshCookie(result.refreshToken(), jwtProperties.refreshExpirationSeconds());
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .body(ApiResponse.ok("토큰이 갱신되었습니다.", new LoginResponse(result.accessToken())));
    }

    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<Void>> logout(
            @CookieValue(name = REFRESH_TOKEN_COOKIE, required = false) String refreshToken) {

        if (refreshToken != null) {
            authService.logout(refreshToken);
        }

        ResponseCookie expiredCookie = buildRefreshCookie("", 0);
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, expiredCookie.toString())
                .body(ApiResponse.ok("로그아웃되었습니다.", null));
    }

    private ResponseCookie buildRefreshCookie(String value, long maxAge) {
        return ResponseCookie.from(REFRESH_TOKEN_COOKIE, value)
                .httpOnly(true)
                .secure(jwtProperties.refreshCookieSecure())
                .sameSite("Strict")
                .path("/api/v1/auth")
                .maxAge(maxAge)
                .build();
    }
}
