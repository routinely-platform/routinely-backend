package com.routinely.web.config;

import com.routinely.web.filter.GatewayAuthFilter;
import com.routinely.web.handler.GlobalExceptionHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 자동설정이 어떤 프로파일에서 GatewayAuthFilter를 등록하는지 검증한다 (#208).
 *
 * <p>서비스는 prod 프로파일로 테스트 컨텍스트를 띄울 수 없다(실제 DB 접속값 필요).
 * 그래서 prod를 포함한 프로파일별 등록 여부는 자동설정 단위로 여기서 본다.
 */
@DisplayName("WebAutoConfiguration")
class WebAutoConfigurationTest {

    // PropertyPlaceholderAutoConfiguration이 빠지면 해석 못 한 "${gateway.secret}"이 글자 그대로 주입돼
    // 시크릿 누락이 기동 실패로 드러나지 않는다. 실제 Boot 앱에는 항상 포함되므로 같은 조건으로 맞춘다.
    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    PropertyPlaceholderAutoConfiguration.class,
                    JacksonAutoConfiguration.class,
                    WebAutoConfiguration.class));

    @ParameterizedTest(name = "프로파일 [{0}]")
    @ValueSource(strings = {"", "test", "prod", "prod,observability"})
    @DisplayName("local이 아닌 프로파일에서는 GatewayAuthFilter와 GlobalExceptionHandler가 등록된다")
    void register_nonLocalProfile_filterRegistered(String profiles) {
        runner.withInitializer(ctx -> ctx.getEnvironment().setActiveProfiles(split(profiles)))
                .withPropertyValues("gateway.secret=secret")
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(GatewayAuthFilter.class);
                    assertThat(ctx).hasSingleBean(GlobalExceptionHandler.class);
                });
    }

    @ParameterizedTest(name = "프로파일 [{0}]")
    @ValueSource(strings = {"local", "local,test", "local,observability"})
    @DisplayName("local 프로파일에서는 GatewayAuthFilter가 등록되지 않고 시크릿도 요구하지 않는다")
    void register_localProfile_filterNotRegistered(String profiles) {
        runner.withInitializer(ctx -> ctx.getEnvironment().setActiveProfiles(split(profiles)))
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx).doesNotHaveBean(GatewayAuthFilter.class);
                    assertThat(ctx).hasSingleBean(GlobalExceptionHandler.class);
                });
    }

    @Test
    @DisplayName("local이 아닌 프로파일에서 gateway.secret이 없으면 기동이 실패한다")
    void register_nonLocalWithoutSecret_failsToStart() {
        runner.withInitializer(ctx -> ctx.getEnvironment().setActiveProfiles("prod"))
                .run(ctx -> assertThat(ctx).hasFailed()
                        .getFailure().rootCause().hasMessageContaining("gateway.secret"));
    }

    @Test
    @DisplayName("local이 아닌 프로파일에서 gateway.secret이 비어 있으면 기동이 실패한다")
    void register_nonLocalWithBlankSecret_failsToStart() {
        runner.withInitializer(ctx -> ctx.getEnvironment().setActiveProfiles("prod"))
                .withPropertyValues("gateway.secret=")
                .run(ctx -> assertThat(ctx).hasFailed()
                        .getFailure().rootCause().hasMessageContaining("gateway.secret"));
    }

    private static String[] split(String profiles) {
        return profiles.isEmpty() ? new String[0] : profiles.split(",");
    }
}
