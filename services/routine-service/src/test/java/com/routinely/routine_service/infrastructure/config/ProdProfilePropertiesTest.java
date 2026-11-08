package com.routinely.routine_service.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.Environment;
import org.springframework.core.env.StandardEnvironment;

/**
 * prod 프로파일의 플레이스홀더 해석을 본다(#204).
 *
 * <p>전체 컨텍스트를 띄우지 않고 설정 파일만 로드한다. {@code @SpringBootTest}로 prod를 띄우면
 * Postgres·Redis·Kafka·Eureka에 붙으려 해 실패 원인이 환경변수 누락인지 인프라 부재인지 구분되지 않는다.
 */
class ProdProfilePropertiesTest {

    private static final Map<String, String> REQUIRED = Map.of(
            "DB_HOST", "db.internal",
            "DB_USERNAME", "routine_app",
            "DB_PASSWORD", "prod-password",
            "GATEWAY_SECRET", "prod-gateway-secret");

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            // 실행 환경(개발자 셸·CI)의 환경변수·시스템 속성이 결과를 바꾸지 않도록 먼저 떼어 낸다.
            // 이니셜라이저는 등록 순서대로 실행되므로 ConfigData 로드보다 앞에 둔다
            .withInitializer(context -> {
                var sources = context.getEnvironment().getPropertySources();
                sources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
                sources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
            })
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withPropertyValues("spring.profiles.active=prod");

    @Test
    @DisplayName("필수 값이 주어지면 prod 설정이 그 값으로 바인딩된다")
    void prod_requiredValuesGiven_bound() {
        runner.withPropertyValues(toPairs(REQUIRED))
                .withPropertyValues("DB_PORT=6543", "EUREKA_HOST=eureka.internal")
                .run(context -> {
                    Environment env = context.getEnvironment();
                    assertThat(env.getProperty("spring.datasource.url"))
                            .isEqualTo("jdbc:postgresql://db.internal:6543/routine_db");
                    assertThat(env.getProperty("spring.datasource.username")).isEqualTo("routine_app");
                    assertThat(env.getProperty("spring.datasource.password")).isEqualTo("prod-password");
                    assertThat(env.getProperty("gateway.secret")).isEqualTo("prod-gateway-secret");
                    assertThat(env.getProperty("eureka.client.service-url.defaultZone"))
                            .isEqualTo("http://eureka.internal:8761/eureka/");
                    assertThat(env.getProperty("spring.grpc.server.reflection.enabled", Boolean.class))
                            .isFalse();
                });
    }

    @Test
    @DisplayName("DB_PORT · EUREKA_HOST는 없어도 기본값으로 해석된다")
    void prod_optionalValuesMissing_defaultsApplied() {
        runner.withPropertyValues(toPairs(REQUIRED))
                .run(context -> {
                    Environment env = context.getEnvironment();
                    assertThat(env.getProperty("spring.datasource.url"))
                            .isEqualTo("jdbc:postgresql://db.internal:5432/routine_db");
                    assertThat(env.getProperty("eureka.client.service-url.defaultZone"))
                            .isEqualTo("http://registry-service:8761/eureka/");
                });
    }

    @ParameterizedTest(name = "{0}가 없으면 {1}를 해석하지 못한다")
    @MethodSource("requiredPlaceholders")
    @DisplayName("필수 환경변수가 없으면 플레이스홀더 미해석으로 실패한다")
    void prod_requiredValueMissing_unresolved(String missing, String property) {
        Map<String, String> given = new HashMap<>(REQUIRED);
        given.remove(missing);

        runner.withPropertyValues(toPairs(given))
                .run(context -> assertThatThrownBy(() -> context.getEnvironment().getProperty(property))
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining(missing));
    }

    static Stream<Arguments> requiredPlaceholders() {
        return Stream.of(
                Arguments.of("DB_HOST", "spring.datasource.url"),
                Arguments.of("DB_USERNAME", "spring.datasource.username"),
                Arguments.of("DB_PASSWORD", "spring.datasource.password"),
                Arguments.of("GATEWAY_SECRET", "gateway.secret"));
    }

    private static String[] toPairs(Map<String, String> values) {
        return values.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .toArray(String[]::new);
    }
}
