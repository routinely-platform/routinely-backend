package com.routinely.gateway_service.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import java.util.Base64;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("JwtTokenProvider")
class JwtTokenProviderTest {

    private static final String SECRET = Base64.getEncoder()
            .encodeToString("test-secret-key-for-hmac-sha256-algorithm!".getBytes());

    private JwtTokenProvider jwtTokenProvider;
    private SecretKey key;

    @BeforeEach
    void setUp() {
        jwtTokenProvider = new JwtTokenProvider(SECRET);
        key = Keys.hmacShaKeyFor(Base64.getDecoder().decode(SECRET));
    }

    @Test
    @DisplayName("유효한 토큰 검증 성공")
    void validateToken_validToken_returnsTrue() {
        String token = createToken(1L, "USER", 60_000);

        assertThat(jwtTokenProvider.validateToken(token)).isTrue();
    }

    @Test
    @DisplayName("만료된 토큰 검증 실패")
    void validateToken_expiredToken_returnsFalse() {
        String token = createToken(1L, "USER", -1_000);

        assertThat(jwtTokenProvider.validateToken(token)).isFalse();
    }

    @Test
    @DisplayName("잘못된 서명 토큰 검증 실패")
    void validateToken_invalidSignature_returnsFalse() {
        SecretKey wrongKey = Keys.hmacShaKeyFor(
                "wrong-secret-key-for-hmac-sha256-algorithm!!".getBytes());
        String token = Jwts.builder()
                .claim("id", 1L)
                .signWith(wrongKey)
                .compact();

        assertThat(jwtTokenProvider.validateToken(token)).isFalse();
    }

    @Test
    @DisplayName("빈 문자열 토큰 검증 실패")
    void validateToken_emptyToken_returnsFalse() {
        assertThat(jwtTokenProvider.validateToken("")).isFalse();
    }

    @Test
    @DisplayName("claims에서 userId 추출 성공")
    void parseClaims_extractsUserId() {
        String token = createToken(42L, "USER", 60_000);

        Claims claims = jwtTokenProvider.parseClaims(token);

        assertThat(claims.get("id", Long.class)).isEqualTo(42L);
    }

    @Test
    @DisplayName("claims에서 role 추출 성공")
    void parseClaims_extractsRole() {
        String token = createToken(1L, "ADMIN", 60_000);

        Claims claims = jwtTokenProvider.parseClaims(token);

        assertThat(claims.get("role", String.class)).isEqualTo("ADMIN");
    }

    private String createToken(Long userId, String role, long validityMillis) {
        Date now = new Date();
        return Jwts.builder()
                .subject("test@routinely.com")
                .claim("id", userId)
                .claim("role", role)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + validityMillis))
                .signWith(key)
                .compact();
    }
}
