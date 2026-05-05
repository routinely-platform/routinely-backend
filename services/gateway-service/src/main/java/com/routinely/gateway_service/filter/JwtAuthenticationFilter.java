package com.routinely.gateway_service.filter;

import com.routinely.core.constant.HeaderConstants;
import com.routinely.core.exception.ErrorCode;
import com.routinely.gateway_service.security.JwtTokenProvider;
import io.jsonwebtoken.Claims;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.cors.reactive.CorsUtils;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

@Component
@Profile("!local")
public class JwtAuthenticationFilter implements GlobalFilter, Ordered {

    private static final String BEARER_PREFIX = "Bearer ";

    private static final Set<String> PUBLIC_ENDPOINTS = Set.of(
            "POST /api/v1/auth/signup",
            "POST /api/v1/auth/login",
            "POST /api/v1/auth/refresh",
            "POST /api/v1/auth/logout"
    );

    private final JwtTokenProvider jwtTokenProvider;
    private final String gatewaySecret;

    public JwtAuthenticationFilter(
            JwtTokenProvider jwtTokenProvider,
            @Value("${gateway.secret}") String gatewaySecret) {
        this.jwtTokenProvider = jwtTokenProvider;
        this.gatewaySecret = gatewaySecret;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        if (CorsUtils.isPreFlightRequest(exchange.getRequest())) {
            return chain.filter(exchange);
        }

        if (isPublicPath(exchange)) {
            return chain.filter(withGatewaySecret(exchange));
        }

        String token = extractToken(exchange);
        if (token == null || !jwtTokenProvider.validateToken(token)) {
            return unauthorized(exchange);
        }

        Claims claims = jwtTokenProvider.parseClaims(token);
        String userId = extractUserId(claims);
        if (userId == null) {
            return unauthorized(exchange);
        }

        ServerHttpRequest mutatedRequest = exchange.getRequest().mutate()
                .header(HeaderConstants.USER_ID, userId)
                .header(HeaderConstants.GATEWAY_SECRET, gatewaySecret)
                .build();

        return chain.filter(exchange.mutate().request(mutatedRequest).build());
    }

    private boolean isPublicPath(ServerWebExchange exchange) {
        String method = exchange.getRequest().getMethod().name();
        String path = exchange.getRequest().getPath().value();
        return PUBLIC_ENDPOINTS.contains(method + " " + path);
    }

    private String extractToken(ServerWebExchange exchange) {
        String authorization = exchange.getRequest().getHeaders()
                .getFirst(HttpHeaders.AUTHORIZATION);
        if (authorization != null && authorization.startsWith(BEARER_PREFIX)) {
            return authorization.substring(BEARER_PREFIX.length());
        }
        return null;
    }

    private ServerWebExchange withGatewaySecret(ServerWebExchange exchange) {
        ServerHttpRequest mutatedRequest = exchange.getRequest().mutate()
                .header(HeaderConstants.GATEWAY_SECRET, gatewaySecret)
                .build();
        return exchange.mutate().request(mutatedRequest).build();
    }

    private String extractUserId(Claims claims) {
        Object userIdClaim = claims.get("id");
        if (userIdClaim instanceof Number number) {
            return Long.toString(number.longValue());
        }
        if (userIdClaim instanceof String value) {
            try {
                return Long.toString(Long.parseLong(value));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private Mono<Void> unauthorized(ServerWebExchange exchange) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);

        String body = """
                {"success":false,"errorCode":"%s","message":"%s"}""".formatted(
                ErrorCode.UNAUTHORIZED.getCode(),
                ErrorCode.UNAUTHORIZED.getMessage());

        DataBuffer buffer = response.bufferFactory()
                .wrap(body.getBytes(StandardCharsets.UTF_8));
        return response.writeWith(Mono.just(buffer));
    }

    @Override
    public int getOrder() {
        return 0;
    }
}
