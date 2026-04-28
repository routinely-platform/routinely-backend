package com.routinely.gateway_service.filter;

import com.routinely.core.constant.HeaderConstants;
import com.routinely.gateway_service.security.JwtTokenProvider;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import javax.crypto.SecretKey;
import java.util.Base64;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("JwtAuthenticationFilter")
class JwtAuthenticationFilterTest {

    private static final String SECRET = Base64.getEncoder()
            .encodeToString("test-secret-key-for-hmac-sha256-algorithm!".getBytes());
    private static final String GATEWAY_SECRET = "test-gateway-secret";

    private JwtAuthenticationFilter filter;
    private GatewayFilterChain chain;
    private SecretKey key;

    @BeforeEach
    void setUp() {
        JwtTokenProvider jwtTokenProvider = new JwtTokenProvider(SECRET);
        filter = new JwtAuthenticationFilter(jwtTokenProvider, GATEWAY_SECRET);
        chain = mock(GatewayFilterChain.class);
        when(chain.filter(any())).thenReturn(Mono.empty());
        key = Keys.hmacShaKeyFor(Base64.getDecoder().decode(SECRET));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/v1/auth/login",
            "/api/v1/auth/signup",
            "/api/v1/auth/refresh",
            "/api/v1/auth/logout"
    })
    @DisplayName("공개경로_JWT없이_통과하고_X-Gateway-Secret_주입")
    void filter_publicPath_passesWithoutJwtAndInjectsGatewaySecret(String path) {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post(path));

        filter.filter(exchange, chain).block();

        ArgumentCaptor<ServerWebExchange> captor = ArgumentCaptor.forClass(ServerWebExchange.class);
        verify(chain).filter(captor.capture());
        assertThat(exchange.getResponse().getStatusCode()).isNotEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(captor.getValue().getRequest().getHeaders()
                .getFirst(HeaderConstants.GATEWAY_SECRET)).isEqualTo(GATEWAY_SECRET);
    }

    @Test
    @DisplayName("인증경로_유효한토큰_X-User-Id_주입_통과")
    void filter_validToken_injectsUserIdAndPasses() {
        String token = createToken(42L, 60_000);
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/users/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token));

        filter.filter(exchange, chain).block();

        ArgumentCaptor<ServerWebExchange> captor = ArgumentCaptor.forClass(ServerWebExchange.class);
        verify(chain).filter(captor.capture());
        assertThat(captor.getValue().getRequest().getHeaders()
                .getFirst(HeaderConstants.USER_ID)).isEqualTo("42");
        assertThat(captor.getValue().getRequest().getHeaders()
                .getFirst(HeaderConstants.GATEWAY_SECRET)).isEqualTo(GATEWAY_SECRET);
    }

    @Test
    @DisplayName("인증경로_토큰없음_401응답")
    void filter_noToken_respondsUnauthorized() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/users/me"));

        filter.filter(exchange, chain).block();

        verify(chain, never()).filter(any());
        assertUnauthorizedResponse(exchange);
    }

    @Test
    @DisplayName("인증경로_만료된토큰_401응답")
    void filter_expiredToken_respondsUnauthorized() {
        String token = createToken(1L, -1_000);
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/routines")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token));

        filter.filter(exchange, chain).block();

        verify(chain, never()).filter(any());
        assertUnauthorizedResponse(exchange);
    }

    @Test
    @DisplayName("인증경로_잘못된형식_Authorization_헤더_401응답")
    void filter_malformedAuthHeader_respondsUnauthorized() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/challenges")
                        .header(HttpHeaders.AUTHORIZATION, "Basic abc123"));

        filter.filter(exchange, chain).block();

        verify(chain, never()).filter(any());
        assertUnauthorizedResponse(exchange);
    }

    @Test
    @DisplayName("인증경로_id클레임없음_401응답")
    void filter_missingIdClaim_respondsUnauthorized() {
        String token = createTokenWithoutId(60_000);
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/users/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token));

        filter.filter(exchange, chain).block();

        verify(chain, never()).filter(any());
        assertUnauthorizedResponse(exchange);
    }

    @Test
    @DisplayName("필터순서_0")
    void getOrder_returnsZero() {
        assertThat(filter.getOrder()).isEqualTo(0);
    }

    private String createToken(Long userId, long validityMillis) {
        Date now = new Date();
        return Jwts.builder()
                .subject("test@routinely.com")
                .claim("id", userId)
                .claim("role", "USER")
                .issuedAt(now)
                .expiration(new Date(now.getTime() + validityMillis))
                .signWith(key)
                .compact();
    }

    private String createTokenWithoutId(long validityMillis) {
        Date now = new Date();
        return Jwts.builder()
                .subject("test@routinely.com")
                .claim("role", "USER")
                .issuedAt(now)
                .expiration(new Date(now.getTime() + validityMillis))
                .signWith(key)
                .compact();
    }

    private void assertUnauthorizedResponse(MockServerWebExchange exchange) {
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(exchange.getResponse().getBodyAsString().block())
                .contains("\"errorCode\":\"UNAUTHORIZED\"");
    }
}
