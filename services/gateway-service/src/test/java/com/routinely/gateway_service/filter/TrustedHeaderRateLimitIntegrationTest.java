package com.routinely.gateway_service.filter;

import com.routinely.core.constant.HeaderConstants;
import io.netty.handler.codec.http.HttpResponseStatus;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 공개 경로에서 클라이언트가 {@code X-User-Id}를 바꿔 보내도 Rate Limit이 IP 키 하나로 집계되는지
 * 실제 필터 순서 · 실제 Redis(Lua 스크립트)로 확인한다(#154).
 *
 * <p>{@code local} 프로파일을 켜지 않아 {@link JwtAuthenticationFilter}까지 실제 운영 순서대로 돈다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DisplayName("신뢰 헤더 정화 + Rate Limit 통합")
class TrustedHeaderRateLimitIntegrationTest {

    private static final int CAPACITY = 30;
    private static final String IP_KEY_PREFIX = "rate_limit:ip:";
    private static final String LOOPBACK_IPV4 = "127.0.0.1";
    private static final String LOOPBACK_IPV6 = "0:0:0:0:0:0:0:1";
    private static final String LOGIN_PATH = "/api/v1/auth/login";
    private static final String LOGIN_BODY = """
            {"email":"victim@routinely.com","password":"guess"}""";

    @SuppressWarnings("resource")
    static final GenericContainer<?> redisContainer = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    /** 업스트림(user-service 목)이 받은 요청마다 {@code X-User-Id} 값 목록. 헤더가 없으면 빈 목록이다. */
    private static final List<List<String>> upstreamUserIds = new CopyOnWriteArrayList<>();
    private static final List<List<String>> upstreamGatewaySecrets = new CopyOnWriteArrayList<>();

    private static DisposableServer userServiceServer;

    @Autowired
    private ReactiveStringRedisTemplate redisTemplate;

    @LocalServerPort
    private int port;

    private WebTestClient webTestClient;

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        if (!redisContainer.isRunning()) {
            redisContainer.start();
        }
        if (userServiceServer == null) {
            userServiceServer = HttpServer.create()
                    .port(0)
                    .route(routes -> routes.route(
                            request -> true,
                            (request, response) -> {
                                upstreamUserIds.add(request.requestHeaders().getAll(HeaderConstants.USER_ID));
                                upstreamGatewaySecrets.add(
                                        request.requestHeaders().getAll(HeaderConstants.GATEWAY_SECRET));
                                return response.status(HttpResponseStatus.OK)
                                        .sendString(Mono.just("user-service-ok"));
                            }))
                    .bindNow();
        }

        registry.add("spring.data.redis.host", redisContainer::getHost);
        registry.add("spring.data.redis.port", redisContainer::getFirstMappedPort);
        registry.add("spring.cloud.discovery.client.simple.instances.user-service[0].uri",
                () -> "http://localhost:" + userServiceServer.port());
        registry.add("spring.cloud.discovery.client.simple.order", () -> "-1");
        registry.add("rate-limit.capacity", () -> CAPACITY);
        registry.add("rate-limit.refill-period-seconds", () -> 60);
        registry.add("rate-limit.trusted-proxies", () -> "");
    }

    @AfterAll
    static void stopServers() {
        if (userServiceServer != null) {
            userServiceServer.disposeNow();
            userServiceServer = null;
        }
        if (redisContainer.isRunning()) {
            redisContainer.stop();
        }
    }

    @BeforeEach
    void setUp() {
        upstreamUserIds.clear();
        upstreamGatewaySecrets.clear();
        redisTemplate.execute(connection -> connection.serverCommands().flushAll()).blockLast();

        webTestClient = WebTestClient.bindToServer()
                .responseTimeout(Duration.ofSeconds(5))
                .baseUrl("http://localhost:" + port)
                .build();
    }

    @Test
    @DisplayName("공개 경로에 X-User-Id를 매번 바꿔 보내도 IP 키 하나로 집계되어 31번째 요청이 429다")
    void login_rotatingForgedUserId_rejectedAt31stByIpKey() {
        for (int i = 1; i <= CAPACITY; i++) {
            login(String.valueOf(i)).expectStatus().isOk();
        }

        login(String.valueOf(CAPACITY + 1))
                .expectStatus().isEqualTo(HttpResponseStatus.TOO_MANY_REQUESTS.code())
                .expectHeader().exists(HttpHeaders.RETRY_AFTER)
                .expectBody()
                .jsonPath("$.errorCode").isEqualTo("TOO_MANY_REQUESTS");

        List<String> keys = rateLimitKeys();
        assertThat(keys).hasSize(1);
        assertLoopbackIpKey(keys.getFirst());
        assertThat(redisTemplate.opsForValue().get(keys.getFirst()).block())
                .isEqualTo(String.valueOf(CAPACITY + 1));

        assertThat(upstreamUserIds).hasSize(CAPACITY).allSatisfy(values -> assertThat(values).isEmpty());
        assertThat(upstreamGatewaySecrets).hasSize(CAPACITY)
                .allSatisfy(values -> assertThat(values).containsExactly("test-gateway-secret"));
    }

    @Test
    @DisplayName("X-User-Id 없이 보낸 요청과 결과가 같다 — 31번째 요청이 429이고 키는 IP 키 하나다")
    void login_withoutUserId_sameResultAsForged() {
        for (int i = 1; i <= CAPACITY; i++) {
            login(null).expectStatus().isOk();
        }

        login(null).expectStatus().isEqualTo(HttpResponseStatus.TOO_MANY_REQUESTS.code());

        List<String> keys = rateLimitKeys();
        assertThat(keys).hasSize(1);
        assertLoopbackIpKey(keys.getFirst());
        assertThat(upstreamUserIds).hasSize(CAPACITY);
    }

    private WebTestClient.ResponseSpec login(String forgedUserId) {
        return webTestClient.post()
                .uri(LOGIN_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .headers(headers -> {
                    if (forgedUserId != null) {
                        headers.set(HeaderConstants.USER_ID, forgedUserId);
                        headers.set(HeaderConstants.GATEWAY_SECRET, "forged-secret");
                    }
                })
                .bodyValue(LOGIN_BODY)
                .exchange();
    }

    /**
     * 키가 실제 원격 주소로 만들어졌는지 본다. 주소가 유실되면 {@code RateLimitFilter}는
     * {@code rate_limit:ip:unknown}을 쓰는데, 접두사 검증만으로는 이를 잡지 못한다.
     *
     * <p>테스트 클라이언트는 같은 호스트에서 {@code localhost}로 붙는다. {@code RateLimitFilter}는
     * {@code InetAddress.getHostAddress()}로 키를 만들므로 루프백은 정확히 아래 두 문자열 중 하나다.
     * {@code InetAddress.getByName()}으로 판정하지 않는다 — 호스트명({@code localhost})도 루프백으로 통과시키고
     * 이름 해석 설정에 따라 DNS 조회가 일어날 수 있다.
     *
     * <p>클라이언트가 루프백이라 "원격 주소를 무시하고 {@code 127.0.0.1}로 고정"하는 회귀는 여기서 구별되지 않는다.
     * 그 회귀는 {@code RateLimitFilterTest}의 remoteAddress 케이스(루프백이 아닌 주소 → 같은 IP 키)가 잡는다.
     */
    private void assertLoopbackIpKey(String key) {
        assertThat(key).startsWith(IP_KEY_PREFIX);
        assertThat(key.substring(IP_KEY_PREFIX.length()))
                .as("rate limit key IP")
                .isIn(LOOPBACK_IPV4, LOOPBACK_IPV6);
    }

    private List<String> rateLimitKeys() {
        return redisTemplate.keys("rate_limit:*").collectList().block();
    }
}
