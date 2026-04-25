package com.routinely.user_service.presentation.rest.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.routinely.core.response.ApiResponse;
import com.routinely.user_service.application.auth.AuthService;
import com.routinely.user_service.application.auth.dto.LoginResult;
import com.routinely.user_service.infrastructure.config.JwtProperties;
import com.routinely.user_service.presentation.rest.auth.dto.request.LoginRequest;
import com.routinely.user_service.presentation.rest.auth.dto.response.LoginResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;

@DisplayName("AuthController")
class AuthControllerTest {

    @Test
    @DisplayName("로그인_로컬설정이면_refresh쿠키에_Secure를_넣지않는다")
    void login_whenRefreshCookieSecureDisabled_omitsSecureAttribute() {
        AuthService authService = mock(AuthService.class);
        JwtProperties jwtProperties = new JwtProperties("secret", 900_000L, 604_800_000L, false);
        AuthController controller = new AuthController(authService, jwtProperties);

        when(authService.login(any())).thenReturn(new LoginResult("access-token", "refresh-token"));

        ResponseEntity<ApiResponse<LoginResponse>> response = controller.login(
                new LoginRequest("user@routinely.com", "password123!")
        );

        String setCookie = response.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
        assertThat(setCookie).contains("refresh_token=refresh-token");
        assertThat(setCookie).doesNotContain("Secure");
    }

    @Test
    @DisplayName("로그인_운영설정이면_refresh쿠키에_Secure를_넣는다")
    void login_whenRefreshCookieSecureEnabled_includesSecureAttribute() {
        AuthService authService = mock(AuthService.class);
        JwtProperties jwtProperties = new JwtProperties("secret", 900_000L, 604_800_000L, true);
        AuthController controller = new AuthController(authService, jwtProperties);

        when(authService.login(any())).thenReturn(new LoginResult("access-token", "refresh-token"));

        ResponseEntity<ApiResponse<LoginResponse>> response = controller.login(
                new LoginRequest("user@routinely.com", "password123!")
        );

        String setCookie = response.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
        assertThat(setCookie).contains("refresh_token=refresh-token");
        assertThat(setCookie).contains("Secure");
    }
}
