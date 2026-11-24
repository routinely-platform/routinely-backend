package com.routinely.gateway_service.filter;

import java.net.InetSocketAddress;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 실제 {@code application.yaml} · {@code application-prod.yaml}로 {@code rate-limit.trusted-proxy-count}가 어떻게
 * 해석되는지 본다(#156).
 *
 * <p>기본값은 0(X-Forwarded-For 무시)이고, prod는 기본값이 없어 값이 누락되거나 비면 기동에 실패해야 한다.
 * 필터 빈 하나만 띄워 설정 파일의 값이 생성자까지 닿는지만 확인한다.
 */
@DisplayName("Rate Limit 신뢰 프록시 대수 설정")
class RateLimitTrustedProxyCountConfigTest {

    private static final String COUNT_ENV = "RATE_LIMIT_TRUSTED_PROXY_COUNT";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            // SpringApplication과 같은 변환 규칙(빈 문자열 → 숫자 변환 실패)을 쓴다.
            .withInitializer(context -> context.getBeanFactory()
                    .setConversionService(ApplicationConversionService.getSharedInstance()))
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withBean(ReactiveStringRedisTemplate.class, RateLimitTrustedProxyCountConfigTest::allowingRedis)
            .withBean("rateLimitFilter", RateLimitFilter.class);

    @Test
    @DisplayName("기본프로파일_값미지정_0으로기동하고XForwardedFor를무시한다")
    void defaultProfile_withoutValue_startsWithZeroAndIgnoresForwardedFor() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();

            assertKeyFor(context, "rate_limit:ip:10.0.0.10");
        });
    }

    @Test
    @DisplayName("prod프로파일_값1_기동하고XForwardedFor오른쪽끝값을쓴다")
    void prodProfile_withOne_startsAndUsesRightmostForwardedValue() {
        runner.withPropertyValues("spring.profiles.active=prod", COUNT_ENV + "=1")
                .run(context -> {
                    assertThat(context).hasNotFailed();

                    assertKeyFor(context, "rate_limit:ip:198.51.100.7");
                });
    }

    @Test
    @DisplayName("prod프로파일_값누락_기동실패")
    void prodProfile_withoutValue_failsToStart() {
        runner.withPropertyValues("spring.profiles.active=prod")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure()
                        .rootCause()
                        .hasMessageContaining(COUNT_ENV));
    }

    @Test
    @DisplayName("prod프로파일_빈값_기동실패")
    void prodProfile_withEmptyValue_failsToStart() {
        runner.withPropertyValues("spring.profiles.active=prod", COUNT_ENV + "=")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure()
                        .hasMessageContaining("rateLimitFilter"));
    }

    @Test
    @DisplayName("prod프로파일_음수_기동실패")
    void prodProfile_withNegativeValue_failsToStart() {
        runner.withPropertyValues("spring.profiles.active=prod", COUNT_ENV + "=-1")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure()
                        .rootCause()
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("rate-limit.trusted-proxy-count"));
    }

    /** Nginx(10.0.0.10)를 거친 공개 경로 요청 하나를 흘려 만들어진 Rate Limit 키를 본다. */
    private static void assertKeyFor(AssertableApplicationContext context, String expectedKey) {
        RateLimitFilter filter = context.getBean(RateLimitFilter.class);
        ReactiveStringRedisTemplate redisTemplate = context.getBean(ReactiveStringRedisTemplate.class);
        GatewayFilterChain chain = mock(GatewayFilterChain.class);
        when(chain.filter(any())).thenReturn(Mono.empty());

        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/v1/auth/login")
                        .remoteAddress(new InetSocketAddress("10.0.0.10", 12345))
                        .header("X-Forwarded-For", "203.0.113.5, 198.51.100.7"));
        filter.filter(exchange, chain).block();

        verify(redisTemplate).execute(any(), eq(List.of(expectedKey)), any(), any());
    }

    private static ReactiveStringRedisTemplate allowingRedis() {
        ReactiveStringRedisTemplate redisTemplate = mock(ReactiveStringRedisTemplate.class);
        when(redisTemplate.execute(any(), anyList(), any(), any())).thenReturn(Flux.just(List.of(1L, 60L)));
        return redisTemplate;
    }
}
