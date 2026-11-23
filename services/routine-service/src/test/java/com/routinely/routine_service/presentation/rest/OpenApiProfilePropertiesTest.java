package com.routinely.routine_service.presentation.rest;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.DisplayName;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.StandardEnvironment;

/**
 * 프로파일별 OpenAPI 명세 활성 여부(#201) — 설정 파일만 로드해 본다(prod 컨텍스트는 띄울 수 없다).
 * local만 켜고, 기본 · prod는 끈다.
 */
@DisplayName("OpenAPI 프로파일 설정")
class OpenApiProfilePropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            // 실행 환경의 환경변수 · 시스템 속성이 결과를 바꾸지 않도록 ConfigData 로드 전에 뗀다
            .withInitializer(context -> {
                var sources = context.getEnvironment().getPropertySources();
                sources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
                sources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
            })
            .withInitializer(new ConfigDataApplicationContextInitializer());

    @ParameterizedTest(name = "프로파일 [{0}] → {1}")
    @CsvSource({"default, false", "prod, false", "local, true"})
    @DisplayName("springdoc.api-docs.enabled는 local에서만 true다")
    void apiDocsEnabled_onlyLocal(String profile, boolean expected) {
        ApplicationContextRunner configured = "default".equals(profile)
                ? runner
                : runner.withPropertyValues("spring.profiles.active=" + profile);
        configured.run(context -> assertThat(context.getEnvironment()
                .getProperty("springdoc.api-docs.enabled", Boolean.class)).isEqualTo(expected));
    }
}
