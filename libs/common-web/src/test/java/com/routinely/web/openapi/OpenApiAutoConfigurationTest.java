package com.routinely.web.openapi;

import static org.assertj.core.api.Assertions.assertThat;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.parameters.Parameter;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

@DisplayName("OpenApiAutoConfiguration")
class OpenApiAutoConfigurationTest {

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    PropertyPlaceholderAutoConfiguration.class, OpenApiAutoConfiguration.class))
            .withPropertyValues("spring.application.name=sample-service");

    @Test
    @DisplayName("명세가 켜지면 servers는 / 하나, 전역 Bearer, 제목은 서비스 이름이다")
    void enabled_registersOpenApi() {
        runner.withPropertyValues("springdoc.api-docs.enabled=true").run(context -> {
            OpenAPI openApi = context.getBean(OpenAPI.class);
            assertThat(openApi.getServers()).singleElement()
                    .satisfies(server -> assertThat(server.getUrl()).isEqualTo("/"));
            assertThat(openApi.getSecurity()).singleElement()
                    .satisfies(requirement -> assertThat(requirement).containsOnlyKeys(OpenApiAutoConfiguration.BEARER_SCHEME));
            assertThat(openApi.getComponents().getSecuritySchemes().get(OpenApiAutoConfiguration.BEARER_SCHEME).getScheme())
                    .isEqualTo("bearer");
            assertThat(openApi.getInfo().getTitle()).isEqualTo("sample-service API");
        });
    }

    @Test
    @DisplayName("명세가 꺼진 프로파일(prod 기본)에서는 등록되지 않는다")
    void disabled_notRegistered() {
        runner.withPropertyValues("springdoc.api-docs.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(OpenAPI.class)
                        .doesNotHaveBean(OperationCustomizer.class));
    }

    @Test
    @DisplayName("springdoc이 없는 서비스(chat · notification)에서는 등록되지 않는다")
    void springdocAbsent_notRegistered() {
        runner.withClassLoader(new FilteredClassLoader(OperationCustomizer.class))
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(OpenApiAutoConfiguration.class));
    }

    @Test
    @DisplayName("서블릿 웹 앱이 아니면 등록되지 않는다")
    void nonServlet_notRegistered() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(OpenApiAutoConfiguration.class))
                .run(context -> assertThat(context).doesNotHaveBean(OpenApiAutoConfiguration.class));
    }

    @Test
    @DisplayName("X-User-Id · X-Gateway-Secret 헤더 파라미터만 대소문자와 무관하게 지우고, 같은 이름의 query는 둔다")
    void hideTrustedHeaders_removesOnlyHeaderParameters() {
        runner.withPropertyValues("springdoc.api-docs.enabled=true").run(context -> {
            Operation operation = new Operation().parameters(new ArrayList<>(List.of(
                    new Parameter().in("header").name("x-user-id"),
                    new Parameter().in("header").name("X-Gateway-Secret"),
                    new Parameter().in("query").name("X-User-Id"),
                    new Parameter().in("header").name("X-Request-Id"))));

            context.getBean(OperationCustomizer.class).customize(operation, null);

            assertThat(operation.getParameters()).extracting(p -> p.getIn() + ":" + p.getName())
                    .containsExactly("query:X-User-Id", "header:X-Request-Id");
        });
    }

    @Test
    @DisplayName("파라미터가 신뢰 헤더뿐이면 parameters를 비운다(빈 배열을 남기지 않는다)")
    void hideTrustedHeaders_onlyTrustedHeaders_nullsParameters() {
        runner.withPropertyValues("springdoc.api-docs.enabled=true").run(context -> {
            Operation operation = new Operation().parameters(new ArrayList<>(List.of(
                    new Parameter().in("header").name("X-User-Id"))));

            context.getBean(OperationCustomizer.class).customize(operation, null);

            assertThat(operation.getParameters()).isNull();
        });
    }
}
