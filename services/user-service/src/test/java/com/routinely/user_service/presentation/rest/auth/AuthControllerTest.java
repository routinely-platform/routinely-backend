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
import com.routinely.user_service.presentation.rest.auth.dto.response.AuthSessionResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

@DisplayName("AuthController")
class AuthControllerTest {

    @Test
    @DisplayName("로그인_로컬설정이면_refresh쿠키에_Secure를_넣지않는다")
    void login_whenRefreshCookieSecureDisabled_omitsSecureAttribute() {
        AuthService authService = mock(AuthService.class);
        JwtProperties jwtProperties = new JwtProperties("secret", 900_000L, 604_800_000L, false);
        AuthController controller = new AuthController(authService, jwtProperties);

        when(authService.login(any())).thenReturn(new LoginResult(
                "access-token",
                "refresh-token",
                1L,
                "user@routinely.com",
                "루틴러",
                null
        ));

        ResponseEntity<ApiResponse<AuthSessionResponse>> response = controller.login(
                new LoginRequest("user@routinely.com", "password123!")
        );

        String setCookie = response.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getData()).isNotNull();
        assertThat(response.getBody().getData().accessToken()).isEqualTo("access-token");
        assertThat(response.getBody().getData().user().userId()).isEqualTo(1L);
        assertThat(response.getBody().getData().user().email()).isEqualTo("user@routinely.com");
        assertThat(response.getBody().getData().user().nickname()).isEqualTo("루틴러");
        assertThat(response.getBody().getData().user().profileImageUrl()).isNull();
        assertThat(setCookie).contains("refresh_token=refresh-token");
        assertThat(setCookie).doesNotContain("Secure");
    }

    @Test
    @DisplayName("로그인_운영설정이면_refresh쿠키에_Secure를_넣는다")
    void login_whenRefreshCookieSecureEnabled_includesSecureAttribute() {
        AuthService authService = mock(AuthService.class);
        JwtProperties jwtProperties = new JwtProperties("secret", 900_000L, 604_800_000L, true);
        AuthController controller = new AuthController(authService, jwtProperties);

        when(authService.login(any())).thenReturn(new LoginResult(
                "access-token",
                "refresh-token",
                1L,
                "user@routinely.com",
                "루틴러",
                null
        ));

        ResponseEntity<ApiResponse<AuthSessionResponse>> response = controller.login(
                new LoginRequest("user@routinely.com", "password123!")
        );

        String setCookie = response.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
        assertThat(setCookie).contains("refresh_token=refresh-token");
        assertThat(setCookie).contains("Secure");
    }

    @Test
    @DisplayName("토큰갱신_성공하면_accessToken과_사용자정보를_반환한다")
    void refresh_success_returnsAuthSessionResponse() {
        AuthService authService = mock(AuthService.class);
        JwtProperties jwtProperties = new JwtProperties("secret", 900_000L, 604_800_000L, true);
        AuthController controller = new AuthController(authService, jwtProperties);

        when(authService.refresh("refresh-token")).thenReturn(new LoginResult(
                "new-access-token",
                "new-refresh-token",
                1L,
                "user@routinely.com",
                "루틴러",
                "https://cdn.routinely.com/profile.jpg"
        ));

        ResponseEntity<ApiResponse<AuthSessionResponse>> response = controller.refresh("refresh-token");

        String setCookie = response.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getData()).isNotNull();
        assertThat(response.getBody().getData().accessToken()).isEqualTo("new-access-token");
        assertThat(response.getBody().getData().user().userId()).isEqualTo(1L);
        assertThat(response.getBody().getData().user().email()).isEqualTo("user@routinely.com");
        assertThat(response.getBody().getData().user().nickname()).isEqualTo("루틴러");
        assertThat(response.getBody().getData().user().profileImageUrl())
                .isEqualTo("https://cdn.routinely.com/profile.jpg");
        assertThat(setCookie).contains("refresh_token=new-refresh-token");
        assertThat(setCookie).contains("Secure");
    }
}
