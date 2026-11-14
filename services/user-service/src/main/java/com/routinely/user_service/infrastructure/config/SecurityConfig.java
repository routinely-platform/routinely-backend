package com.routinely.user_service.infrastructure.config;

import com.routinely.core.time.RoutinelyClocks;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Clock;

@Configuration
@EnableConfigurationProperties({JwtProperties.class, UserProfileProperties.class})
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public Clock clock() {
        // 마이크로초 Clock — DB 컬럼 정밀도와 맞춘다(#213). 시간대는 그대로다.
        return RoutinelyClocks.micros(Clock.systemDefaultZone());
    }
}
