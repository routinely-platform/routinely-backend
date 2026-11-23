package com.routinely.web.openapi;

import com.routinely.core.constant.HeaderConstants;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import java.util.List;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;

/**
 * 서비스 OpenAPI 명세의 공통 설정(#201). user · routine · challenge가 공유한다.
 *
 * <p><b>조건</b> — springdoc은 common-web에 {@code compileOnly}라서, springdoc이 없는 서비스(chat · notification)에서는
 * {@link ConditionalOnClass}로 이 설정 전체가 빠진다. springdoc 타입은 이 클래스 밖으로 새지 않는다.
 * 명세가 꺼진 프로파일({@code springdoc.api-docs.enabled=false}, prod 기본)에서도 등록하지 않는다.
 *
 * <ul>
 *   <li><b>servers</b> — {@code /}. UI가 게이트웨이 origin에서 서빙되므로 Try it out이 게이트웨이로 간다.
 *       경로에 이미 {@code /api/v1}이 있어 접두사를 붙이지 않는다</li>
 *   <li><b>security</b> — 전역 Bearer. 공개 API는 컨트롤러에 빈 {@code @SecurityRequirements}를 달아 {@code security: []}로 뺀다</li>
 *   <li><b>신뢰 헤더 숨김</b> — {@code X-User-Id} · {@code X-Gateway-Secret}은 게이트웨이가 넣는 값이라 입력란에서 뺀다.
 *       <b>숨기는 것은 보안 통제가 아니다.</b> 클라이언트가 보낸 값은 게이트웨이가 지우고 덮어쓴다(#154 · ADR-0019)</li>
 * </ul>
 */
@AutoConfiguration
@ConditionalOnClass({OpenAPI.class, OperationCustomizer.class})
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(name = "springdoc.api-docs.enabled", havingValue = "true", matchIfMissing = true)
public class OpenApiAutoConfiguration {

    public static final String BEARER_SCHEME = "bearerAuth";

    private static final List<String> TRUSTED_HEADERS = List.of(
            HeaderConstants.USER_ID,
            HeaderConstants.GATEWAY_SECRET);

    @Bean
    public OpenAPI routinelyOpenApi(@Value("${spring.application.name:application}") String applicationName) {
        return new OpenAPI()
                .info(new Info()
                        .title(applicationName + " API")
                        .version("v1")
                        .description("게이트웨이 Swagger UI(/swagger-ui.html)에서 호출한다. "
                                + "보호 API는 로그인 응답의 accessToken을 Authorize에 넣는다."))
                .servers(List.of(new Server().url("/").description("게이트웨이 origin")))
                .components(new Components().addSecuritySchemes(BEARER_SCHEME, new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME));
    }

    @Bean
    public OperationCustomizer hideTrustedHeaders() {
        return (operation, handlerMethod) -> {
            List<Parameter> parameters = operation.getParameters();
            if (parameters != null) {
                parameters.removeIf(OpenApiAutoConfiguration::isTrustedHeader);
                if (parameters.isEmpty()) {
                    operation.setParameters(null);
                }
            }
            return operation;
        };
    }

    static boolean isTrustedHeader(Parameter parameter) {
        return "header".equals(parameter.getIn())
                && parameter.getName() != null
                && TRUSTED_HEADERS.stream().anyMatch(parameter.getName()::equalsIgnoreCase);
    }
}
