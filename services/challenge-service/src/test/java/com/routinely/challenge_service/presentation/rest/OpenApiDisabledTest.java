package com.routinely.challenge_service.presentation.rest;

import static org.assertj.core.api.Assertions.assertThat;

import com.routinely.web.openapi.OpenApiContract;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springdoc.webmvc.api.OpenApiWebMvcResource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

/**
 * 명세가 꺼진 기본 설정(local이 아닌 모든 프로파일, prod 포함)에서 /v3/api-docs가 열리지 않는지 본다(#201).
 *
 * <p>prod로는 컨텍스트를 띄울 수 없어(실제 DB 접속값 필요) 기본값을 그대로 쓰는 test 프로파일로 본다.
 * 시크릿이 없으면 GatewayAuthFilter가 먼저 401을 내 핸들러 유무가 가려지므로 <b>유효한 시크릿을 붙인다.</b>
 * 핸들러 부재는 빈으로 직접 보고, HTTP는 "명세가 나오지 않는다"까지만 본다(상태 코드는 아래 주석).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DisplayName("OpenAPI 비노출 (기본 설정)")
class OpenApiDisabledTest {

    @LocalServerPort
    private int port;

    @Value("${gateway.secret}")
    private String gatewaySecret;

    @Autowired
    private ApplicationContext context;

    @Test
    @DisplayName("명세 핸들러가 등록되지 않는다")
    void apiDocsHandler_notRegistered() {
        assertThat(context.getBeansOfType(OpenApiWebMvcResource.class)).isEmpty();
    }

    @Test
    @DisplayName("유효한 시크릿으로 요청해도 /v3/api-docs가 명세를 돌려주지 않는다")
    void apiDocs_withGatewaySecret_notServed() {
        HttpResponse<String> response = OpenApiContract.get(port, "/v3/api-docs", gatewaySecret);

        // 상태 코드를 404로 못 박지 않는다. 없는 경로는 지금 GlobalExceptionHandler가 500으로 바꾼다 — #217.
        // 그 수정이 들어오면 이 단언을 404로 좁힌다
        assertThat(response.statusCode()).isNotEqualTo(200);
        assertThat(response.body()).doesNotContain("\"openapi\"");
    }
}
