package com.routinely.gateway_service.controller;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.netty.handler.codec.http.HttpResponseStatus;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"test", "local"})
@DisplayName("Circuit Breaker Integration")
class CircuitBreakerIntegrationTest {

    private static final AtomicInteger userServiceRequestCount = new AtomicInteger();
    private static final AtomicInteger routineTimeoutRequestCount = new AtomicInteger();
    private static final AtomicInteger routineSlowRequestCount = new AtomicInteger();

    private static DisposableServer userServiceServer;
    private static DisposableServer routineServiceServer;

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @MockitoBean
    private ReactiveStringRedisTemplate redisTemplate;

    @LocalServerPort
    private int port;

    private WebTestClient webTestClient;

    @BeforeAll
    static void startBackendServers() {
        userServiceServer = HttpServer.create()
                .port(0)
                .route(routes -> routes.route(
                        request -> true,
                        (request, response) -> {
                            userServiceRequestCount.incrementAndGet();
                            return response.status(HttpResponseStatus.INTERNAL_SERVER_ERROR)
                                    .sendString(Mono.just("user-service-error"));
                        }))
                .bindNow();

        routineServiceServer = HttpServer.create()
                .port(0)
                .route(routes -> {
                    routes.get("/api/v1/routines/timeout", (request, response) -> {
                        routineTimeoutRequestCount.incrementAndGet();
                        return response.status(HttpResponseStatus.OK)
                                .sendString(Mono.just("timeout-response")
                                        .delayElement(Duration.ofMillis(800)));
                    });
                    routes.get("/api/v1/routines/slow-open", (request, response) -> {
                        routineSlowRequestCount.incrementAndGet();
                        return response.status(HttpResponseStatus.OK)
                                .sendString(Mono.just("slow-response")
                                        .delayElement(Duration.ofMillis(300)));
                    });
                    routes.route(
                            request -> true,
                            (request, response) -> response.status(HttpResponseStatus.OK)
                                    .sendString(Mono.just("routine-ok")));
                })
                .bindNow();
    }

    @AfterAll
    static void stopBackendServers() {
        if (userServiceServer != null) {
            userServiceServer.disposeNow();
        }
        if (routineServiceServer != null) {
            routineServiceServer.disposeNow();
        }
    }

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.cloud.discovery.client.simple.instances.user-service[0].uri",
                () -> "http://localhost:" + userServiceServer.port());
        registry.add("spring.cloud.discovery.client.simple.instances.routine-service[0].uri",
                () -> "http://localhost:" + routineServiceServer.port());
        registry.add("spring.cloud.discovery.client.simple.order", () -> "-1");
        registry.add("resilience4j.timelimiter.instances.routine-service-cb.timeoutDuration",
                () -> "500ms");
        registry.add("resilience4j.circuitbreaker.instances.routine-service-cb.slowCallDurationThreshold",
                () -> "200ms");
    }

    @BeforeEach
    void setUp() {
        userServiceRequestCount.set(0);
        routineTimeoutRequestCount.set(0);
        routineSlowRequestCount.set(0);

        webTestClient = WebTestClient.bindToServer()
                .responseTimeout(Duration.ofSeconds(5))
                .baseUrl("http://localhost:" + port)
                .build();

        circuitBreakerRegistry.circuitBreaker("user-service-cb").reset();
        circuitBreakerRegistry.circuitBreaker("routine-service-cb").reset();
        circuitBreakerRegistry.circuitBreaker("challenge-service-cb").reset();
        circuitBreakerRegistry.circuitBreaker("chat-service-cb").reset();
        circuitBreakerRegistry.circuitBreaker("notification-service-cb").reset();

        when(redisTemplate.execute(any(), anyList(), any(), any()))
                .thenReturn(Flux.just(List.of(1L, 60L)));
    }

    @Test
    @DisplayName("업스트림 500 응답은 fallback 503으로 변환된다")
    void userService5xxResponse_returnsFallbackResponse() {
        webTestClient.post()
                .uri("/api/v1/auth/login")
                .exchange()
                .expectStatus().isEqualTo(HttpResponseStatus.SERVICE_UNAVAILABLE.code())
                .expectBody()
                .jsonPath("$.success").isEqualTo(false)
                .jsonPath("$.errorCode").isEqualTo("SERVICE_UNAVAILABLE")
                .jsonPath("$.message").isNotEmpty();

        assertThat(userServiceRequestCount.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("routine-service 지연 응답은 TimeLimiter timeout 후 fallback 된다")
    void routineServiceTimeout_returnsFallbackResponse() {
        webTestClient.get()
                .uri("/api/v1/routines/timeout")
                .exchange()
                .expectStatus().isEqualTo(HttpResponseStatus.SERVICE_UNAVAILABLE.code())
                .expectBody()
                .jsonPath("$.success").isEqualTo(false)
                .jsonPath("$.errorCode").isEqualTo("SERVICE_UNAVAILABLE");

        assertThat(routineTimeoutRequestCount.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("routine-service의 느린 호출이 누적되면 circuit 이 열려 이후 요청은 upstream 호출 없이 fallback 된다")
    void routineServiceSlowCalls_openCircuitBreaker() {
        for (int i = 0; i < 5; i++) {
            webTestClient.get()
                    .uri("/api/v1/routines/slow-open")
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody(String.class)
                    .isEqualTo("slow-response");
        }

        webTestClient.get()
                .uri("/api/v1/routines/slow-open")
                .exchange()
                .expectStatus().isEqualTo(HttpResponseStatus.SERVICE_UNAVAILABLE.code())
                .expectBody()
                .jsonPath("$.errorCode").isEqualTo("SERVICE_UNAVAILABLE");

        assertThat(routineSlowRequestCount.get()).isEqualTo(5);
    }
}
