package com.routinely.challenge_service.infrastructure.config;

import com.routinely.core.time.RoutinelyClocks;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        // 마이크로초 Clock — DB 컬럼 정밀도와 맞춘다(#213). 시간대는 그대로다.
        return RoutinelyClocks.micros(Clock.system(ZoneId.of("Asia/Seoul")));
    }
}
