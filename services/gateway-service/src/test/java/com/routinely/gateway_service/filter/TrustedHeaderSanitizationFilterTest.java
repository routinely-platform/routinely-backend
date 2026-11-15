package com.routinely.gateway_service.filter;

import com.routinely.core.constant.HeaderConstants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("TrustedHeaderSanitizationFilter")
class TrustedHeaderSanitizationFilterTest {

    private TrustedHeaderSanitizationFilter filter;
    private GatewayFilterChain chain;

    @BeforeEach
    void setUp() {
        filter = new TrustedHeaderSanitizationFilter();
        chain = mock(GatewayFilterChain.class);
        when(chain.filter(any())).thenReturn(Mono.empty());
    }

    @Test
    @DisplayName("클라이언트가 보낸 X-User-Id와 X-Gateway-Secret을 지운다")
    void filter_trustedHeaders_removed() {
        HttpHeaders forwarded = filterAndCapture(MockServerHttpRequest.post("/api/v1/auth/login")
                .header(HeaderConstants.USER_ID, "42")
                .header(HeaderConstants.GATEWAY_SECRET, "forged-secret"));

        assertThat(forwarded.containsHeader(HeaderConstants.USER_ID)).isFalse();
        assertThat(forwarded.containsHeader(HeaderConstants.GATEWAY_SECRET)).isFalse();
    }

    @Test
    @DisplayName("신뢰 헤더가 아닌 헤더는 그대로 둔다")
    void filter_otherHeaders_preserved() {
        HttpHeaders forwarded = filterAndCapture(MockServerHttpRequest.get("/api/v1/users/me")
                .header(HeaderConstants.USER_ID, "42")
                .header(HttpHeaders.AUTHORIZATION, "Bearer token")
                .header(HttpHeaders.CONTENT_TYPE, "application/json")
                .header("X-Forwarded-For", "203.0.113.7")
                .header("X-Request-Id", "req-1"));

        assertThat(forwarded.getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer token");
        assertThat(forwarded.getFirst(HttpHeaders.CONTENT_TYPE)).isEqualTo("application/json");
        assertThat(forwarded.getFirst("X-Forwarded-For")).isEqualTo("203.0.113.7");
        assertThat(forwarded.getFirst("X-Request-Id")).isEqualTo("req-1");
        assertThat(forwarded.containsHeader(HeaderConstants.USER_ID)).isFalse();
    }

    @Test
    @DisplayName("헤더명 대소문자가 달라도 지운다")
    void filter_differentCaseHeaderNames_removed() {
        HttpHeaders forwarded = filterAndCapture(MockServerHttpRequest.post("/api/v1/auth/login")
                .header("x-user-id", "42")
                .header("X-GATEWAY-SECRET", "forged-secret"));

        assertThat(forwarded.containsHeader(HeaderConstants.USER_ID)).isFalse();
        assertThat(forwarded.containsHeader(HeaderConstants.GATEWAY_SECRET)).isFalse();
    }

    @Test
    @DisplayName("같은 헤더에 값이 여러 개여도 전부 지운다")
    void filter_multipleValues_allRemoved() {
        HttpHeaders forwarded = filterAndCapture(MockServerHttpRequest.post("/api/v1/auth/login")
                .header(HeaderConstants.USER_ID, "1", "2")
                .header(HeaderConstants.GATEWAY_SECRET, "a", "b"));

        assertThat(forwarded.get(HeaderConstants.USER_ID)).isNull();
        assertThat(forwarded.get(HeaderConstants.GATEWAY_SECRET)).isNull();
    }

    @Test
    @DisplayName("헤더 말고는 요청을 건드리지 않는다 — 원격 주소 · 메서드 · 경로가 그대로다")
    void filter_remoteAddressMethodAndPath_preserved() {
        InetSocketAddress remoteAddress = new InetSocketAddress("203.0.113.7", 12345);
        filter.filter(MockServerWebExchange.from(MockServerHttpRequest.post("/api/v1/auth/login")
                .remoteAddress(remoteAddress)
                .header(HeaderConstants.USER_ID, "42")), chain).block();

        ArgumentCaptor<ServerWebExchange> captor = ArgumentCaptor.forClass(ServerWebExchange.class);
        verify(chain).filter(captor.capture());
        ServerHttpRequest forwarded = captor.getValue().getRequest();
        assertThat(forwarded.getRemoteAddress()).isEqualTo(remoteAddress);
        assertThat(forwarded.getMethod()).isEqualTo(HttpMethod.POST);
        assertThat(forwarded.getPath().value()).isEqualTo("/api/v1/auth/login");
    }

    @Test
    @DisplayName("필터 순서는 HIGHEST_PRECEDENCE로 JWT 필터와 Rate Limit 필터보다 먼저 돈다")
    void getOrder_returnsHighestPrecedence() {
        assertThat(filter.getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE);
        assertThat(filter.getOrder()).isLessThan(0);
    }

    private HttpHeaders filterAndCapture(MockServerHttpRequest.BaseBuilder<?> request) {
        filter.filter(MockServerWebExchange.from(request), chain).block();

        ArgumentCaptor<ServerWebExchange> captor = ArgumentCaptor.forClass(ServerWebExchange.class);
        verify(chain).filter(captor.capture());
        return captor.getValue().getRequest().getHeaders();
    }
}
