package com.routinely.gateway_service.docs;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.context.ApplicationContext;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * prod에서 Swagger UI · 명세 · 문서 라우트가 열리지 않는지 본다(#201).
 *
 * <p>prod 설정 파일을 그대로 쓰고({@code prod}), 실행에 필요한 값만 {@code test}가 덮는다. 문서 경로는 JWT 공개
 * 경로에 들어 있으므로 토큰 없이 요청해도 401이 아니라 <b>라우트가 없어 404</b>여야 한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "rate-limit.trusted-proxies=")
@ActiveProfiles({"prod", "test"})
@DisplayName("API 문서 비노출 (prod 게이트웨이)")
class ApiDocsProdExposureTest {

    @MockitoBean
    private ReactiveStringRedisTemplate redisTemplate;

    @LocalServerPort
    private int port;

    @Autowired
    private ApplicationContext context;

    @Autowired
    private RouteLocator routeLocator;

    private WebTestClient client;

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .responseTimeout(Duration.ofSeconds(5))
                .build();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "/swagger-ui.html", "/swagger-ui/index.html", "/v3/api-docs", "/v3/api-docs/swagger-config",
            // springdoc을 꺼도 Boot 정적 매핑이 켜져 있으면 jar 안의 UI 파일이 열린다(spring.web.resources.add-mappings=false)
            "/webjars/swagger-ui/index.html", "/webjars/swagger-ui/5.32.2/index.html"})
    @DisplayName("Swagger UI · 화면 파일 · 게이트웨이 명세가 404다")
    void swaggerUiAndApiDocs_notFound(String path) {
        client.get().uri(path).exchange().expectStatus().isNotFound();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"user-service", "routine-service", "challenge-service"})
    @DisplayName("문서 경로는 라우트가 없어 404다")
    void apiDocsRoute_notFound(String service) {
        client.get().uri(ApiDocsRoutes.PATH_PREFIX + service).exchange().expectStatus().isNotFound();
    }

    @Test
    @DisplayName("문서 라우트 빈과 라우트가 등록되지 않는다")
    void apiDocsRoutes_notRegistered() {
        assertThat(context.getBeansOfType(ApiDocsRouteConfig.class)).isEmpty();
        assertThat(routeLocator.getRoutes().collectList().block())
                .noneMatch(route -> route.getId().startsWith("api-docs-"));
    }
}
