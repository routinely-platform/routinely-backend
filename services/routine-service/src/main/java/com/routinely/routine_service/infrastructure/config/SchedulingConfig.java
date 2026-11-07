package com.routinely.routine_service.infrastructure.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * {@code @Scheduled} 작업을 켠다. 키가 없으면 켜지고 {@code false}면 꺼진다.
 *
 * <p>{@code spring.task.scheduling.enabled}는 Spring Boot 공식 설정이 아니라 이 조건이 읽는 프로젝트 설정이다.
 * 테스트는 {@code false}로 꺼서 폴러가 다른 테스트 실행 중 DB·Redis에 접근하지 않게 한다.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "spring.task.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {
}
