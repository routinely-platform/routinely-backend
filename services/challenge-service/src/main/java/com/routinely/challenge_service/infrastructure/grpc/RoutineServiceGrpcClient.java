package com.routinely.challenge_service.infrastructure.grpc;

import com.routinely.core.exception.BusinessException;
import com.routinely.proto.routine.ListCategoriesRequest;
import com.routinely.proto.routine.RoutineGrpcServiceGrpc;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.routinely.core.exception.ErrorCode.SERVICE_UNAVAILABLE;

@Slf4j
@Component
public class RoutineServiceGrpcClient {

    static final String CACHE_KEY = "categories:active";
    static final Duration CACHE_TTL = Duration.ofHours(24);
    static final Duration GRPC_DEADLINE = Duration.ofSeconds(3);
    static final String CIRCUIT_BREAKER_NAME = "routine-service-cb";

    // SADD + EXPIRE를 단일 스크립트로 원자 실행한다.
    // (둘을 분리하면 SADD 성공 후 EXPIRE 실패 시 TTL 없는 키가 영구히 남을 수 있음)
    private static final RedisScript<Long> CACHE_WRITE_SCRIPT = RedisScript.of(
            "redis.call('SADD', KEYS[1], unpack(ARGV, 2)) "
                    + "redis.call('EXPIRE', KEYS[1], ARGV[1]) "
                    + "return 1",
            Long.class);

    private final RoutineGrpcServiceGrpc.RoutineGrpcServiceBlockingStub routineStub;
    private final StringRedisTemplate redisTemplate;
    private final CircuitBreakerFactory<?, ?> circuitBreakerFactory;

    public RoutineServiceGrpcClient(RoutineGrpcServiceGrpc.RoutineGrpcServiceBlockingStub routineStub,
                                    StringRedisTemplate redisTemplate,
                                    CircuitBreakerFactory<?, ?> circuitBreakerFactory) {
        this.routineStub = routineStub;
        this.redisTemplate = redisTemplate;
        this.circuitBreakerFactory = circuitBreakerFactory;
    }

    /**
     * 활성 카테고리 코드 Set을 반환한다. (Cache-Aside, Redis TTL 24h)
     * 캐시 미스 시 routine-service gRPC 호출을 deadline + circuit breaker로 감싸며,
     * 호출 실패·서킷 OPEN 시 챌린지 생성을 차단한다 (Fail Closed).
     */
    public Set<String> getActiveCategoryCodes() {
        Set<String> cached = getCachedCategoryCodes();
        if (!cached.isEmpty()) {
            return cached;
        }

        Set<String> codes = circuitBreakerFactory.create(CIRCUIT_BREAKER_NAME)
                .run(this::fetchActiveCategoryCodes, this::failClosed);
        cacheCategoryCodes(codes);
        return codes;
    }

    private Set<String> getCachedCategoryCodes() {
        try {
            Set<String> members = redisTemplate.opsForSet().members(CACHE_KEY);
            return members != null ? members : Set.of();
        } catch (DataAccessException e) {
            log.warn("카테고리 캐시 조회 실패 — routine-service gRPC로 조회를 계속합니다.", e);
            return Set.of();
        }
    }

    private Set<String> fetchActiveCategoryCodes() {
        var response = routineStub
                .withDeadlineAfter(GRPC_DEADLINE.toMillis(), TimeUnit.MILLISECONDS)
                .listCategories(ListCategoriesRequest.newBuilder().build());
        return response.getCategoriesList().stream()
                .map(item -> item.getCode())
                .collect(Collectors.toSet());
    }

    private void cacheCategoryCodes(Set<String> codes) {
        // 활성 카테고리가 비어 있는 상태는 캐싱하지 않는다.
        // 마스터 데이터가 채워지면 다음 요청에서 바로 반영되도록 매번 재조회한다.
        if (codes.isEmpty()) {
            return;
        }

        try {
            String[] args = new String[codes.size() + 1];
            args[0] = String.valueOf(CACHE_TTL.toSeconds());
            int idx = 1;
            for (String code : codes) {
                args[idx++] = code;
            }
            redisTemplate.execute(CACHE_WRITE_SCRIPT, List.of(CACHE_KEY), (Object[]) args);
        } catch (DataAccessException e) {
            log.warn("카테고리 캐시 저장 실패 — gRPC 조회 결과로 요청 처리를 계속합니다.", e);
        }
    }

    private Set<String> failClosed(Throwable throwable) {
        log.error("routine-service 카테고리 조회 실패 — 챌린지 생성 차단 (Fail Closed)", throwable);
        throw new BusinessException(SERVICE_UNAVAILABLE);
    }
}
