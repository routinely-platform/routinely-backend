package com.routinely.user_service.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("user.profile")
public record UserProfileProperties(
        long nicknameCooldownDays
) {
}
