package com.routinely.jpa.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("JpaAuditingConfig")
class JpaAuditingConfigTest {

    @Test
    @DisplayName("AutoConfiguration어노테이션선언_부트스프링자동등록대상")
    void isMarkedAsAutoConfiguration() {
        assertThat(JpaAuditingConfig.class.isAnnotationPresent(AutoConfiguration.class)).isTrue();
    }

    @Test
    @DisplayName("EnableJpaAuditing어노테이션선언_감사기능활성화")
    void enablesJpaAuditing() {
        assertThat(JpaAuditingConfig.class.isAnnotationPresent(EnableJpaAuditing.class)).isTrue();
    }
}
