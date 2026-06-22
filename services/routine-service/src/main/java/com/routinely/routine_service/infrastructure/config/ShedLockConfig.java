package com.routinely.routine_service.infrastructure.config;

import net.javacrumbs.shedlock.provider.redis.spring.RedisLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;

/**
 * 멀티 인스턴스 환경에서 스케줄러 중복 실행을 방지하는 분산 락 설정. (ADR-0033)
 * challenge-service와 동일하게 Redis 기반 {@code routinely} 환경 키 스페이스를 사용한다.
 */
@Configuration
@EnableSchedulerLock(defaultLockAtMostFor = "5m")
public class ShedLockConfig {

    @Bean
    public RedisLockProvider lockProvider(RedisConnectionFactory connectionFactory) {
        return new RedisLockProvider(connectionFactory, "routinely");
    }
}
