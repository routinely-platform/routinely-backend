package com.routinely.gateway_service.docs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.routinely.core.constant.HeaderConstants;
import com.routinely.gateway_service.support.TestAccessTokens;
import io.netty.handler.codec.http.HttpResponseStatus;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;
import reactor.netty.http.server.HttpServerRequest;

/**
 * local 게이트웨이의 Swagger UI · 문서 라우트 · 인증 흐름(#201).
 *
 * <p>local에서도 {@code JwtAuthenticationFilter}가 켜진다. 업스트림 서비스는 Netty 스텁으로 띄우고, 스텁이 받은
 * 헤더를 기록해 "게이트웨이가 무엇을 넘겼는가"를 본다. 공개 경로의 위조 {@code X-User-Id} 제거와 인증 경로의
 * 덮어쓰기 자체는 #154 단위 테스트가 이미 보므로, 여기서는 문서 경로와 local 프로파일에서 성립하는지만 본다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"test", "local"})
@DisplayName("API 문서 (local 게이트웨이)")
class ApiDocsLocalIntegrationTest {

    private static final long LOGIN_USER_ID = 7L;
    /** 로그인 스텁이 토큰을 서명할 키. 컨텍스트가 해석한 jwt.secret을 매 테스트 전에 넣는다 */
    private static volatile String jwtSecretForStub;
    private static final String REFRESH_COOKIE =
            "refresh_token=refresh-1; Path=/api/v1/auth; Max-Age=604800; HttpOnly; SameSite=Strict";
    private static final String CLEARED_REFRESH_COOKIE =
            "refresh_token=; Path=/api/v1/auth; Max-Age=0; HttpOnly; SameSite=Strict";

    /** 스텁이 마지막으로 받은 요청의 헤더. 키는 "{서비스} {메서드} {경로}" */
    private static final Map<String, HttpHeadersSnapshot> RECEIVED = new ConcurrentHashMap<>();

    private static final Map<String, DisposableServer> UPSTREAMS = new ConcurrentHashMap<>();

    @MockitoBean
    private ReactiveStringRedisTemplate redisTemplate;

    @LocalServerPort
    private int port;

    @Value("${gateway.secret}")
    private String gatewaySecret;

    @Value("${jwt.secret}")
    private String jwtSecret;

    private WebTestClient client;

    @BeforeAll
    static void startUpstreams() {
        for (String service : ApiDocsRoutes.SERVICES) {
            UPSTREAMS.put(service, HttpServer.create().port(0).route(routes -> {
                routes.get("/v3/api-docs", (request, response) -> {
                    record(service, request);
                    return response.header(HttpHeaders.CONTENT_TYPE, "application/json")
                            .sendString(Mono.just("{\"openapi\":\"3.1.0\",\"info\":{\"title\":\"" + service + "\"}}"));
                });
                routes.post("/api/v1/auth/login", (request, response) -> {
                    record(service, request);
                    return response.header(HttpHeaders.SET_COOKIE, REFRESH_COOKIE)
                            .header(HttpHeaders.CONTENT_TYPE, "application/json")
                            .sendString(Mono.fromSupplier(() -> "{\"success\":true,\"data\":{\"accessToken\":\""
                                    + TestAccessTokens.token(jwtSecretForStub, LOGIN_USER_ID) + "\"}}"));
                });
                routes.post("/api/v1/auth/refresh", (request, response) -> {
                    record(service, request);
                    return response.header(HttpHeaders.SET_COOKIE, REFRESH_COOKIE).sendString(Mono.just("{}"));
                });
                routes.post("/api/v1/auth/logout", (request, response) -> {
                    record(service, request);
                    return response.header(HttpHeaders.SET_COOKIE, CLEARED_REFRESH_COOKIE).sendString(Mono.just("{}"));
                });
                routes.get("/api/v1/users/me", (request, response) -> {
                    record(service, request);
                    return response.status(HttpResponseStatus.OK).sendString(Mono.just("{}"));
                });
            }).bindNow());
        }
    }

    @AfterAll
    static void stopUpstreams() {
        UPSTREAMS.values().forEach(DisposableServer::disposeNow);
    }

    @DynamicPropertySource
    static void upstreamInstances(DynamicPropertyRegistry registry) {
        UPSTREAMS.forEach((service, server) -> registry.add(
                "spring.cloud.discovery.client.simple.instances." + service + "[0].uri",
                () -> "http://localhost:" + server.port()));
        registry.add("spring.cloud.discovery.client.simple.order", () -> "-1");
    }

    @BeforeEach
    void setUp() {
        RECEIVED.clear();
        jwtSecretForStub = jwtSecret;
        client = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .responseTimeout(Duration.ofSeconds(5))
                .build();
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(), any()))
                .thenReturn(Flux.just(List.of(1L, 60L)));
    }

    @Test
    @DisplayName("Swagger UI는 토큰 없이 열리고 드롭다운에는 서비스 명세만 있다")
    void swaggerUi_loadsWithoutToken_listsServiceDocsOnly() {
        client.get().uri("/swagger-ui.html").exchange()
                .expectStatus().isFound()
                .expectHeader().valueEquals(HttpHeaders.LOCATION, "/swagger-ui/index.html");
        client.get().uri("/swagger-ui/index.html").exchange().expectStatus().isOk();

        client.get().uri("/v3/api-docs/swagger-config").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.urls.length()").isEqualTo(ApiDocsRoutes.SERVICES.size())
                .jsonPath("$.urls[*].url").value(urls ->
                        assertThat((List<Object>) urls).containsExactlyInAnyOrderElementsOf(List.copyOf(ApiDocsRoutes.paths())))
                .jsonPath("$.url").doesNotExist()
                .jsonPath("$.persistAuthorization").isEqualTo(true)
                .jsonPath("$['urls.primaryName']").isEqualTo("user-service");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "/swagger-ui/swagger-ui.css", "/swagger-ui/index.css",
            "/swagger-ui/swagger-ui-bundle.js", "/swagger-ui/swagger-ui-standalone-preset.js",
            "/swagger-ui/swagger-initializer.js"})
    @DisplayName("index.html이 상대 경로로 부르는 화면 파일은 springdoc 핸들러가 서빙한다(Boot 정적 매핑은 꺼져 있다)")
    void swaggerUiAssets_servedBySpringdoc(String path) {
        client.get().uri(path).exchange().expectStatus().isOk();
    }

    @Test
    @DisplayName("게이트웨이 자체 명세는 빈 문서다(/fallback/** 제외)")
    void gatewayOwnApiDocs_isEmpty() {
        client.get().uri("/v3/api-docs").exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.paths").isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"user-service", "routine-service", "challenge-service"})
    @DisplayName("문서 경로는 토큰 없이 서비스 /v3/api-docs를 가져오고, 시크릿은 넣고 위조 X-User-Id는 지운다")
    void apiDocsRoute_withoutToken_proxiesWithSecretAndWithoutUserId(String service) {
        client.get().uri(ApiDocsRoutes.PATH_PREFIX + service)
                .header(HeaderConstants.USER_ID, "999")
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.info.title").isEqualTo(service);

        HttpHeadersSnapshot upstream = RECEIVED.get(service + " GET /v3/api-docs");
        assertThat(upstream).as("업스트림이 /v3/api-docs로 요청을 받았다").isNotNull();
        assertThat(upstream.get(HeaderConstants.GATEWAY_SECRET)).containsExactly(gatewaySecret);
        assertThat(upstream.get(HeaderConstants.USER_ID)).isEmpty();
    }

    @Test
    @DisplayName("문서 경로도 Rate Limit을 소모하고, 공개 경로라 IP 키로 집계된다")
    void apiDocsRoute_consumesRateLimitByIpKey() {
        client.get().uri(ApiDocsRoutes.PATH_PREFIX + "user-service")
                .header(HeaderConstants.USER_ID, "999")
                .exchange()
                .expectStatus().isOk();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> keys = ArgumentCaptor.forClass(List.class);
        verify(redisTemplate, atLeastOnce()).execute(any(RedisScript.class), keys.capture(), any(), any());
        assertThat(keys.getValue()).singleElement().asString().startsWith("rate_limit:ip:");
    }

    @Test
    @DisplayName("문서 라우트는 GET만 매칭한다 — POST는 라우트가 없어 업스트림에 닿지 않고 404")
    void apiDocsRoute_nonGet_notRouted() {
        // 라우트가 매칭되지 않으면 글로벌 필터(JWT)까지 가지 않는다. "GET만 공개"는 JwtAuthenticationFilterTest가 본다
        client.post().uri(ApiDocsRoutes.PATH_PREFIX + "user-service").exchange()
                .expectStatus().isNotFound();
        assertThat(RECEIVED).isEmpty();
    }

    @Test
    @DisplayName("login으로 받은 토큰으로 보호 API를 부르면 게이트웨이가 토큰의 사용자 ID로 X-User-Id를 덮어쓴다")
    void login_thenProtectedApi_userIdFromToken() {
        Map<?, ?> body = client.post().uri("/api/v1/auth/login")
                .exchange()
                .expectStatus().isOk()
                .expectBody(Map.class).returnResult().getResponseBody();
        String token = (String) ((Map<?, ?>) body.get("data")).get("accessToken");

        client.get().uri("/api/v1/users/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .header(HeaderConstants.USER_ID, "999")
                .exchange()
                .expectStatus().isOk();

        HttpHeadersSnapshot upstream = RECEIVED.get("user-service GET /api/v1/users/me");
        assertThat(upstream.get(HeaderConstants.USER_ID)).containsExactly(Long.toString(LOGIN_USER_ID));
        assertThat(upstream.get(HeaderConstants.GATEWAY_SECRET)).containsExactly(gatewaySecret);
    }

    @Test
    @DisplayName("보호 API는 local에서도 토큰이 없으면 401이다")
    void protectedApi_withoutToken_unauthorized() {
        client.get().uri("/api/v1/users/me")
                .header(HeaderConstants.USER_ID, "1")
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(RECEIVED).doesNotContainKey("user-service GET /api/v1/users/me");
    }

    @Test
    @DisplayName("refresh 쿠키는 login · refresh · logout에서 속성 그대로 오가고, 요청 쿠키도 업스트림에 전달된다")
    void refreshCookie_passesThroughLoginRefreshLogout() {
        client.post().uri("/api/v1/auth/login").exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals(HttpHeaders.SET_COOKIE, REFRESH_COOKIE);

        client.post().uri("/api/v1/auth/refresh")
                .cookie("refresh_token", "refresh-1")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals(HttpHeaders.SET_COOKIE, REFRESH_COOKIE);
        assertThat(RECEIVED.get("user-service POST /api/v1/auth/refresh").get(HttpHeaders.COOKIE))
                .containsExactly("refresh_token=refresh-1");

        client.post().uri("/api/v1/auth/logout")
                .cookie("refresh_token", "refresh-1")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals(HttpHeaders.SET_COOKIE, CLEARED_REFRESH_COOKIE);
        assertThat(RECEIVED.get("user-service POST /api/v1/auth/logout").get(HttpHeaders.COOKIE))
                .containsExactly("refresh_token=refresh-1");
    }

    private static void record(String service, HttpServerRequest request) {
        RECEIVED.put(service + " " + request.method().name() + " " + request.uri(),
                new HttpHeadersSnapshot(request.requestHeaders()));
    }

    /** 스텁이 받은 헤더를 이름별 값 목록으로 보관한다(이름은 대소문자 무시). */
    private record HttpHeadersSnapshot(Map<String, List<String>> values) {

        HttpHeadersSnapshot(io.netty.handler.codec.http.HttpHeaders headers) {
            this(toMap(headers));
        }

        List<String> get(String name) {
            return values.getOrDefault(name.toLowerCase(), List.of());
        }

        private static Map<String, List<String>> toMap(io.netty.handler.codec.http.HttpHeaders headers) {
            Map<String, List<String>> map = new ConcurrentHashMap<>();
            headers.names().forEach(name -> map.put(name.toLowerCase(), headers.getAll(name)));
            return map;
        }
    }
}
