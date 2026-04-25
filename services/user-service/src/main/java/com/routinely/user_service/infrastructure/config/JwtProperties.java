package com.routinely.user_service.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("jwt")
public record JwtProperties(
        String secret,
        long expirationMs,
        long refreshExpirationMs,
        boolean refreshCookieSecure
) {
    public long refreshExpirationSeconds() {
        return refreshExpirationMs / 1000;
    }
}
