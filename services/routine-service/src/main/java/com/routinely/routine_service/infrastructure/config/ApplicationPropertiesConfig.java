package com.routinely.routine_service.infrastructure.config;

import com.routinely.routine_service.application.execution.ChallengeBackfillProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(ChallengeBackfillProperties.class)
public class ApplicationPropertiesConfig {
}
