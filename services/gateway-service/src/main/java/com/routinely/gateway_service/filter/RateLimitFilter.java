package com.routinely.gateway_service.filter;

import com.routinely.core.constant.HeaderConstants;
import com.routinely.core.exception.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Slf4j
@Component
public class RateLimitFilter implements GlobalFilter, Ordered {

    private static final String KEY_PREFIX = "rate_limit:";
    private static final RedisScript<List> RATE_LIMIT_SCRIPT = RedisScript.of("""
            local current = redis.call('INCR', KEYS[1])
            if current == 1 then
                redis.call('EXPIRE', KEYS[1], ARGV[1])
            end

            local ttl = redis.call('TTL', KEYS[1])
            if ttl < 0 then
                ttl = tonumber(ARGV[1])
            end

            if current > tonumber(ARGV[2]) then
                return {0, ttl}
            end

            return {1, ttl}
            """, List.class);

    private final ReactiveStringRedisTemplate redisTemplate;
    private final int capacity;
    private final long refillPeriodSeconds;
    private final Set<String> trustedProxyIps;
    private final boolean failOpenOnRedisError;

    private enum RateLimitDecision {
        ALLOW,
        REJECT,
        SERVICE_UNAVAILABLE
    }

    private record RateLimitResult(RateLimitDecision decision, long retryAfterSeconds) {
    }

    public RateLimitFilter(
            ReactiveStringRedisTemplate redisTemplate,
            @Value("${rate-limit.capacity}") int capacity,
            @Value("${rate-limit.refill-period-seconds}") long refillPeriodSeconds,
            @Value("${rate-limit.trusted-proxies:}") String trustedProxyIps,
            @Value("${rate-limit.fail-open-on-redis-error:false}") boolean failOpenOnRedisError) {
        this.redisTemplate = redisTemplate;
        this.capacity = capacity;
        this.refillPeriodSeconds = refillPeriodSeconds;
        this.trustedProxyIps = Arrays.stream(trustedProxyIps.split(","))
                .map(String::trim)
                .filter(ip -> !ip.isEmpty())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        this.failOpenOnRedisError = failOpenOnRedisError;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String userId = exchange.getRequest().getHeaders().getFirst(HeaderConstants.USER_ID);
        String key = userId != null ? KEY_PREFIX + userId : KEY_PREFIX + "ip:" + resolveClientIp(exchange);

        return resolveRateLimitResult(key)
                .flatMap(result -> switch (result.decision()) {
                    case ALLOW -> chain.filter(exchange);
                    case REJECT -> tooManyRequests(exchange, result.retryAfterSeconds());
                    case SERVICE_UNAVAILABLE -> serviceUnavailable(exchange);
                });
    }

    private String resolveClientIp(ServerWebExchange exchange) {
        String remoteAddress = resolveRemoteAddress(exchange);
        if (!trustedProxyIps.contains(remoteAddress)) {
            return remoteAddress;
        }

        String xForwardedFor = exchange.getRequest().getHeaders().getFirst("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isBlank()) {
            return xForwardedFor.split(",")[0].trim();
        }
        return remoteAddress;
    }

    private String resolveRemoteAddress(ServerWebExchange exchange) {
        var address = exchange.getRequest().getRemoteAddress();
        if (address == null) {
            return "unknown";
        }
        if (address.getAddress() != null) {
            return address.getAddress().getHostAddress();
        }
        return address.getHostString();
    }

    private Mono<RateLimitResult> allowRequest(String key) {
        return redisTemplate.execute(
                        RATE_LIMIT_SCRIPT,
                        List.of(key),
                        Long.toString(refillPeriodSeconds),
                        Integer.toString(capacity))
                .next()
                .map(this::toRateLimitResult);
    }

    private RateLimitResult toRateLimitResult(List result) {
        long allowed = toLong(result.get(0));
        long ttl = result.size() > 1 ? toLong(result.get(1)) : refillPeriodSeconds;
        long retryAfterSeconds = ttl > 0 ? ttl : refillPeriodSeconds;
        RateLimitDecision decision = allowed == 1L ? RateLimitDecision.ALLOW : RateLimitDecision.REJECT;
        return new RateLimitResult(decision, retryAfterSeconds);
    }

    private long toLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(String.valueOf(value));
    }

    private Mono<RateLimitResult> resolveRateLimitResult(String key) {
        return allowRequest(key)
                .onErrorResume(throwable -> handleRedisFailure(key, throwable));
    }

    private Mono<RateLimitResult> handleRedisFailure(String key, Throwable throwable) {
        log.error("Rate limit Redis 처리 중 오류가 발생했습니다. key={}", key, throwable);

        if (failOpenOnRedisError) {
            return Mono.just(new RateLimitResult(RateLimitDecision.ALLOW, 0));
        }

        return Mono.just(new RateLimitResult(RateLimitDecision.SERVICE_UNAVAILABLE, 0));
    }

    private Mono<Void> tooManyRequests(ServerWebExchange exchange, long retryAfterSeconds) {
        exchange.getResponse().getHeaders().set(HttpHeaders.RETRY_AFTER, Long.toString(retryAfterSeconds));
        return writeErrorResponse(exchange, HttpStatus.TOO_MANY_REQUESTS, ErrorCode.TOO_MANY_REQUESTS);
    }

    private Mono<Void> serviceUnavailable(ServerWebExchange exchange) {
        return writeErrorResponse(exchange, HttpStatus.SERVICE_UNAVAILABLE, ErrorCode.SERVICE_UNAVAILABLE);
    }

    private Mono<Void> writeErrorResponse(
            ServerWebExchange exchange,
            HttpStatus status,
            ErrorCode errorCode) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);

        String body = """
                {"success":false,"errorCode":"%s","message":"%s"}""".formatted(
                errorCode.getCode(),
                errorCode.getMessage());

        DataBuffer buffer = response.bufferFactory()
                .wrap(body.getBytes(StandardCharsets.UTF_8));
        return response.writeWith(Mono.just(buffer));
    }

    @Override
    public int getOrder() {
        return 1;
    }
}
