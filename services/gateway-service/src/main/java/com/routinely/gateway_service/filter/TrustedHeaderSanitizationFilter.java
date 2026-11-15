package com.routinely.gateway_service.filter;

import com.routinely.core.constant.HeaderConstants;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 클라이언트가 보낸 신뢰 헤더({@code X-User-Id} · {@code X-Gateway-Secret})를 게이트웨이 진입 시점에 버린다.
 *
 * <p>두 헤더는 게이트웨이만 발급한다. 이 필터가 가장 먼저 돌아 외부 값을 지우고, 뒤이어
 * {@link JwtAuthenticationFilter}(0)가 검증된 값만 다시 넣는다. 그래서 {@link RateLimitFilter}(1)가 읽는
 * {@code X-User-Id}는 항상 게이트웨이가 넣은 값이거나 없다(없으면 IP 키).
 *
 * <p>라우트 {@code RemoveRequestHeader}로는 대신할 수 없다. 라우트 필터는 order 1부터라
 * {@link RateLimitFilter}보다 늦고, 인증 경로에서는 JWT 필터가 넣은 정상 값까지 지운다(ADR-0019).
 *
 * <p>프로파일 조건 없이 항상 켠다.
 */
@Component
public class TrustedHeaderSanitizationFilter implements GlobalFilter, Ordered {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest sanitized = exchange.getRequest().mutate()
                .headers(headers -> {
                    headers.remove(HeaderConstants.USER_ID);
                    headers.remove(HeaderConstants.GATEWAY_SECRET);
                })
                .build();
        return chain.filter(exchange.mutate().request(sanitized).build());
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
