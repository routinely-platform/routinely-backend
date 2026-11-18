package com.routinely.gateway_service.support;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.util.Base64;
import java.util.Date;

/**
 * 통합 테스트에서 게이트웨이 JWT 필터를 통과할 access token을 만든다.
 *
 * <p>local에서도 {@code JwtAuthenticationFilter}가 켜지므로(#201) {@code test,local}로 도는 통합 테스트도
 * 보호 경로에는 토큰이 필요하다. 서명 키는 <b>테스트 컨텍스트가 실제로 해석한 {@code jwt.secret}</b>을 받는다 —
 * 고정 값을 두면 {@code JWT_SECRET}을 바꿔 실행할 때 게이트웨이 키와 어긋난다. 토큰은 쓸 때마다 새로 만든다.
 */
public final class TestAccessTokens {

    private static final long VALIDITY_MILLIS = 5 * 60_000;

    private TestAccessTokens() {
    }

    /** {@code Bearer } 접두사가 붙은 Authorization 헤더 값 */
    public static String bearer(String base64Secret, long userId) {
        return "Bearer " + token(base64Secret, userId);
    }

    public static String token(String base64Secret, long userId) {
        Date issuedAt = new Date();
        return Jwts.builder()
                .subject("test@routinely.com")
                .claim("id", userId)
                .claim("role", "USER")
                .issuedAt(issuedAt)
                .expiration(new Date(issuedAt.getTime() + VALIDITY_MILLIS))
                .signWith(Keys.hmacShaKeyFor(Base64.getDecoder().decode(base64Secret)))
                .compact();
    }
}
