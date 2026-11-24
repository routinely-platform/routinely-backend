package com.routinely.gateway_service.filter;

import com.routinely.core.constant.HeaderConstants;
import java.net.InetSocketAddress;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("RateLimitFilter")
class RateLimitFilterTest {

    private static final int CAPACITY = 3;
    private static final long REFILL_PERIOD = 60L;
    private static final String NGINX_IP = "10.0.0.10";

    private RateLimitFilter filter;
    private GatewayFilterChain chain;
    private ReactiveStringRedisTemplate redisTemplate;

    @BeforeEach
    void setUp() {
        redisTemplate = mock(ReactiveStringRedisTemplate.class);
        when(redisTemplate.execute(any(), anyList(), any(), any())).thenReturn(Flux.just(List.of(1L, REFILL_PERIOD)));

        filter = filterWithTrustedProxyCount(0);
        chain = mock(GatewayFilterChain.class);
        when(chain.filter(any())).thenReturn(Mono.empty());
    }

    @Test
    @DisplayName("인증된요청_capacity이내_통과")
    void filter_authenticatedRequest_withinCapacity_passes() {
        givenCount(1L);
        MockServerWebExchange exchange = exchangeWithUserId("42");

        filter.filter(exchange, chain).block();

        verify(chain).filter(any());
        assertThat(exchange.getResponse().getStatusCode()).isNotEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    @DisplayName("인증된요청_capacity정확히_마지막허용요청_통과")
    void filter_authenticatedRequest_exactlyAtCapacity_passes() {
        givenCount((long) CAPACITY);
        MockServerWebExchange exchange = exchangeWithUserId("42");

        filter.filter(exchange, chain).block();

        verify(chain).filter(any());
    }

    @Test
    @DisplayName("인증된요청_capacity초과_429응답")
    void filter_authenticatedRequest_exceedsCapacity_respondsTooManyRequests() {
        givenRateLimitResult(false, 7L);
        MockServerWebExchange exchange = exchangeWithUserId("42");

        filter.filter(exchange, chain).block();

        verify(chain, never()).filter(any());
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(exchange.getResponse().getBodyAsString().block())
                .contains("\"errorCode\":\"TOO_MANY_REQUESTS\"");
        assertThat(exchange.getResponse().getHeaders().getFirst("Retry-After"))
                .isEqualTo("7");
    }

    @Test
    @DisplayName("요청처리_Lua스크립트에만료시간과capacity전달")
    void filter_request_executesLuaScriptWithRefillPeriodAndCapacity() {
        givenRateLimitResult(true, REFILL_PERIOD);
        MockServerWebExchange exchange = exchangeWithUserId("42");

        filter.filter(exchange, chain).block();

        verify(redisTemplate).execute(any(), eq(List.of("rate_limit:42")), eq("60"), eq("3"));
    }

    @Test
    @DisplayName("인증된요청_userId로Redis키생성")
    void filter_authenticatedRequest_usesUserIdAsKey() {
        givenRateLimitResult(true, REFILL_PERIOD);
        MockServerWebExchange exchange = exchangeWithUserId("99");

        filter.filter(exchange, chain).block();

        verify(redisTemplate).execute(any(), eq(List.of("rate_limit:99")), eq("60"), eq("3"));
    }

    @Test
    @DisplayName("비인증요청_신뢰프록시1대_XForwardedFor오른쪽끝값으로Redis키생성")
    void filter_unauthenticatedRequest_withOneTrustedProxy_usesRightmostForwardedValueAsKey() {
        givenRateLimitResult(true, REFILL_PERIOD);
        filter = filterWithTrustedProxyCount(1);
        MockServerWebExchange exchange = loginRequestFromNginx("203.0.113.5, 198.51.100.7");

        filter.filter(exchange, chain).block();

        verify(redisTemplate).execute(any(), eq(List.of("rate_limit:ip:198.51.100.7")), eq("60"), eq("3"));
    }

    @Test
    @DisplayName("비인증요청_신뢰프록시1대_왼쪽에조작된값을바꿔붙여도_실제클라이언트IP하나로집계")
    void filter_unauthenticatedRequest_withSpoofedLeftmostValues_aggregatesToRealClientIp() {
        givenRateLimitResult(true, REFILL_PERIOD);
        filter = filterWithTrustedProxyCount(1);

        // 공격자가 매번 다른 값을 보내도 Nginx가 오른쪽 끝에 실제 접속 주소를 덧붙인다.
        filter.filter(loginRequestFromNginx("10.9.9.1, 198.51.100.7"), chain).block();
        filter.filter(loginRequestFromNginx("10.9.9.2, 198.51.100.7"), chain).block();

        verify(redisTemplate, times(2))
                .execute(any(), eq(List.of("rate_limit:ip:198.51.100.7")), eq("60"), eq("3"));
    }

    @Test
    @DisplayName("비인증요청_신뢰프록시2대_XForwardedFor오른쪽에서두번째값으로Redis키생성")
    void filter_unauthenticatedRequest_withTwoTrustedProxies_usesSecondValueFromRight() {
        givenRateLimitResult(true, REFILL_PERIOD);
        filter = filterWithTrustedProxyCount(2);
        MockServerWebExchange exchange = loginRequestFromNginx("10.9.9.1, 198.51.100.7, 172.16.0.3");

        filter.filter(exchange, chain).block();

        verify(redisTemplate).execute(any(), eq(List.of("rate_limit:ip:198.51.100.7")), eq("60"), eq("3"));
    }

    @Test
    @DisplayName("비인증요청_신뢰프록시1대_포트붙은값_예외없이원문으로Redis키생성")
    void filter_unauthenticatedRequest_withPortSuffixedForwardedValue_usesRawValueAsKey() {
        givenRateLimitResult(true, REFILL_PERIOD);
        filter = filterWithTrustedProxyCount(1);
        // IP 리터럴이 아니라 resolver가 unresolved 주소(getAddress() == null)를 돌려준다.
        // Nginx는 포트 없는 $remote_addr만 쓰므로 전제가 깨졌을 때만 들어오는 값이다 — 500으로 죽지 않아야 한다.
        MockServerWebExchange exchange = loginRequestFromNginx("203.0.113.5, 1.2.3.4:5678");

        filter.filter(exchange, chain).block();

        verify(chain).filter(any());
        verify(redisTemplate).execute(any(), eq(List.of("rate_limit:ip:1.2.3.4:5678")), eq("60"), eq("3"));
    }

    @Test
    @DisplayName("비인증요청_신뢰프록시1대_XForwardedFor헤더2개_전부버리고remoteAddress로Redis키생성")
    void filter_unauthenticatedRequest_withMultipleForwardedForHeaders_fallsBackToRemoteAddress() {
        givenRateLimitResult(true, REFILL_PERIOD);
        filter = filterWithTrustedProxyCount(1);
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/v1/auth/login")
                        .remoteAddress(new InetSocketAddress(NGINX_IP, 12345))
                        .header("X-Forwarded-For", "10.9.9.1", "198.51.100.7"));

        filter.filter(exchange, chain).block();

        verify(redisTemplate).execute(any(), eq(List.of("rate_limit:ip:" + NGINX_IP)), eq("60"), eq("3"));
    }

    @Test
    @DisplayName("비인증요청_신뢰프록시1대_XForwardedFor없음_remoteAddress로Redis키생성")
    void filter_unauthenticatedRequest_withOneTrustedProxy_withoutForwardedFor_usesRemoteAddress() {
        givenRateLimitResult(true, REFILL_PERIOD);
        filter = filterWithTrustedProxyCount(1);
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/v1/auth/login")
                        .remoteAddress(new InetSocketAddress(NGINX_IP, 12345)));

        filter.filter(exchange, chain).block();

        verify(redisTemplate).execute(any(), eq(List.of("rate_limit:ip:" + NGINX_IP)), eq("60"), eq("3"));
    }

    @Test
    @DisplayName("비인증요청_신뢰프록시1대_XForwardedFor와remoteAddress모두없음_unknown키생성")
    void filter_unauthenticatedRequest_withOneTrustedProxy_withoutAnyAddress_usesUnknownKey() {
        givenRateLimitResult(true, REFILL_PERIOD);
        filter = filterWithTrustedProxyCount(1);
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/v1/auth/login"));

        filter.filter(exchange, chain).block();

        verify(redisTemplate).execute(any(), eq(List.of("rate_limit:ip:unknown")), eq("60"), eq("3"));
    }

    @Test
    @DisplayName("비인증요청_신뢰프록시0대_XForwardedFor무시하고remoteAddress로Redis키생성")
    void filter_unauthenticatedRequest_withZeroTrustedProxies_ignoresForwardedFor() {
        givenRateLimitResult(true, REFILL_PERIOD);
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/v1/auth/login")
                        .remoteAddress(new InetSocketAddress("198.51.100.77", 12345))
                        .header("X-Forwarded-For", "203.0.113.5, 10.0.0.1"));

        filter.filter(exchange, chain).block();

        verify(redisTemplate).execute(any(), eq(List.of("rate_limit:ip:198.51.100.77")), eq("60"), eq("3"));
    }

    @Test
    @DisplayName("비인증요청_XForwardedFor없음_remoteAddress로Redis키생성")
    void filter_unauthenticatedRequest_withoutXForwardedFor_usesRemoteAddressAsKey() {
        givenRateLimitResult(true, REFILL_PERIOD);
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/v1/auth/login")
                        .remoteAddress(new InetSocketAddress("192.168.1.1", 12345)));

        filter.filter(exchange, chain).block();

        verify(redisTemplate).execute(any(), eq(List.of("rate_limit:ip:192.168.1.1")), eq("60"), eq("3"));
    }

    @Test
    @DisplayName("생성_신뢰프록시대수음수_IllegalArgumentException")
    void construct_negativeTrustedProxyCount_throwsException() {
        assertThatThrownBy(() -> filterWithTrustedProxyCount(-1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rate-limit.trusted-proxy-count");
    }

    @Test
    @DisplayName("비인증요청_capacity초과_429응답")
    void filter_unauthenticatedRequest_exceedsCapacity_respondsTooManyRequests() {
        givenRateLimitResult(false, 11L);
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/v1/auth/login")
                        .header("X-Forwarded-For", "203.0.113.5"));

        filter.filter(exchange, chain).block();

        verify(chain, never()).filter(any());
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(exchange.getResponse().getHeaders().getFirst("Retry-After"))
                .isEqualTo("11");
    }

    @Test
    @DisplayName("Redis오류_failClosed설정_503응답")
    void filter_redisFailure_withFailClosed_respondsServiceUnavailable() {
        givenRedisFailure();
        MockServerWebExchange exchange = exchangeWithUserId("42");

        filter.filter(exchange, chain).block();

        verify(chain, never()).filter(any());
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(exchange.getResponse().getBodyAsString().block())
                .contains("\"errorCode\":\"SERVICE_UNAVAILABLE\"");
    }

    @Test
    @DisplayName("Redis오류_failOpen설정_요청통과")
    void filter_redisFailure_withFailOpen_passesRequest() {
        filter = new RateLimitFilter(redisTemplate, CAPACITY, REFILL_PERIOD, 0, true);
        givenRedisFailure();
        MockServerWebExchange exchange = exchangeWithUserId("42");

        filter.filter(exchange, chain).block();

        verify(chain).filter(any());
        assertThat(exchange.getResponse().getStatusCode()).isNull();
    }

    @Test
    @DisplayName("다운스트림예외_Redis예외처리로오인하지않고_그대로전파")
    void filter_downstreamFailure_doesNotUseRedisFallback() {
        MockServerWebExchange exchange = exchangeWithUserId("42");
        RuntimeException downstreamException = new RuntimeException("backend failure");
        when(chain.filter(any())).thenReturn(Mono.error(downstreamException));

        assertThatThrownBy(() -> filter.filter(exchange, chain).block())
                .isSameAs(downstreamException);

        verify(chain).filter(any());
        assertThat(exchange.getResponse().getStatusCode()).isNull();
    }

    @Test
    @DisplayName("필터순서_1")
    void getOrder_returnsOne() {
        assertThat(filter.getOrder()).isEqualTo(1);
    }

    private void givenCount(long count) {
        givenRateLimitResult(count <= CAPACITY, REFILL_PERIOD);
    }

    private void givenRateLimitResult(boolean allowed, long retryAfterSeconds) {
        when(redisTemplate.execute(any(), anyList(), any(), any()))
                .thenReturn(Flux.just(List.of(allowed ? 1L : 0L, retryAfterSeconds)));
    }

    private void givenRedisFailure() {
        when(redisTemplate.execute(any(), anyList(), any(), any()))
                .thenReturn(Flux.error(new RuntimeException("redis unavailable")));
    }

    private RateLimitFilter filterWithTrustedProxyCount(int trustedProxyCount) {
        return new RateLimitFilter(redisTemplate, CAPACITY, REFILL_PERIOD, trustedProxyCount, false);
    }

    /** Nginx({@value #NGINX_IP})를 거쳐 들어온 로그인 요청. {@code forwardedFor}는 Nginx가 덧붙인 뒤의 값이다. */
    private MockServerWebExchange loginRequestFromNginx(String forwardedFor) {
        return MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/v1/auth/login")
                        .remoteAddress(new InetSocketAddress(NGINX_IP, 12345))
                        .header("X-Forwarded-For", forwardedFor));
    }

    private MockServerWebExchange exchangeWithUserId(String userId) {
        return MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/users/me")
                        .header(HeaderConstants.USER_ID, userId));
    }
}
