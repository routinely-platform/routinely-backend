package com.routinely.challenge_service.infrastructure.grpc;

import com.routinely.core.exception.BusinessException;
import com.routinely.core.exception.ErrorCode;
import com.routinely.proto.routine.CategoryItem;
import com.routinely.proto.routine.ListCategoriesRequest;
import com.routinely.proto.routine.ListCategoriesResponse;
import com.routinely.proto.routine.RoutineGrpcServiceGrpc;
import io.grpc.Status;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.cloud.client.circuitbreaker.CircuitBreaker;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

import static com.routinely.challenge_service.infrastructure.grpc.RoutineServiceGrpcClient.CACHE_KEY;
import static com.routinely.challenge_service.infrastructure.grpc.RoutineServiceGrpcClient.CACHE_TTL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("RoutineServiceGrpcClient")
class RoutineServiceGrpcClientTest {

    private RoutineGrpcServiceGrpc.RoutineGrpcServiceBlockingStub routineStub;
    private StringRedisTemplate redisTemplate;
    private SetOperations<String, String> setOperations;
    private CircuitBreakerFactory<?, ?> circuitBreakerFactory;
    private RoutineServiceGrpcClient client;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        routineStub = mock(RoutineGrpcServiceGrpc.RoutineGrpcServiceBlockingStub.class);
        redisTemplate = mock(StringRedisTemplate.class);
        setOperations = mock(SetOperations.class);
        when(redisTemplate.opsForSet()).thenReturn(setOperations);
        when(routineStub.withDeadlineAfter(anyLong(), any())).thenReturn(routineStub);

        // 서킷 브레이커는 supplier를 실행하고, 예외가 나면 fallback으로 위임하는 pass-through로 대체한다.
        // (OPEN/CLOSE 전이 자체는 resilience4j 책임이므로 통합 테스트에서 검증)
        circuitBreakerFactory = mock(CircuitBreakerFactory.class);
        CircuitBreaker circuitBreaker = mock(CircuitBreaker.class);
        when(circuitBreakerFactory.create(anyString())).thenReturn(circuitBreaker);
        when(circuitBreaker.run(any(Supplier.class), any(Function.class))).thenAnswer(invocation -> {
            Supplier<Object> toRun = invocation.getArgument(0);
            Function<Throwable, Object> fallback = invocation.getArgument(1);
            try {
                return toRun.get();
            } catch (Throwable t) {
                return fallback.apply(t);
            }
        });

        client = new RoutineServiceGrpcClient(routineStub, redisTemplate, circuitBreakerFactory);
    }

    @Test
    @DisplayName("Redis에 캐시가 있으면 gRPC를 호출하지 않고 캐시 값을 반환한다")
    void getActiveCategoryCodes_whenCacheHit_returnsCachedValues() {
        when(setOperations.members(CACHE_KEY)).thenReturn(Set.of("EXERCISE", "READING"));

        Set<String> result = client.getActiveCategoryCodes();

        assertThat(result).containsExactlyInAnyOrder("EXERCISE", "READING");
        verify(routineStub, never()).listCategories(any());
    }

    @Test
    @DisplayName("Redis 캐시 미스이면 gRPC를 호출하고 결과를 TTL과 함께 캐싱한 뒤 반환한다")
    @SuppressWarnings("unchecked")
    void getActiveCategoryCodes_whenCacheMiss_callsGrpcAndCachesResult() {
        when(setOperations.members(CACHE_KEY)).thenReturn(null);
        when(routineStub.listCategories(any(ListCategoriesRequest.class))).thenReturn(
                ListCategoriesResponse.newBuilder()
                        .addCategories(CategoryItem.newBuilder().setCode("EXERCISE").build())
                        .addCategories(CategoryItem.newBuilder().setCode("READING").build())
                        .build()
        );

        Set<String> result = client.getActiveCategoryCodes();

        assertThat(result).containsExactlyInAnyOrder("EXERCISE", "READING");
        // SADD + EXPIRE 원자 스크립트: KEYS[1]=캐시 키, ARGV[1]=TTL(초), ARGV[2..]=코드
        ArgumentCaptor<List<String>> keysCaptor = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<Object> argsCaptor = ArgumentCaptor.forClass(Object.class);
        // execute(script, keys, ARGV[1]=TTL, ARGV[2..]=코드) — varargs 3개(TTL + 코드 2개)
        verify(redisTemplate).execute(
                any(RedisScript.class),
                keysCaptor.capture(),
                argsCaptor.capture(), argsCaptor.capture(), argsCaptor.capture());

        assertThat(keysCaptor.getValue()).containsExactly(CACHE_KEY);
        assertThat(argsCaptor.getAllValues())
                .containsExactlyInAnyOrder(String.valueOf(CACHE_TTL.toSeconds()), "EXERCISE", "READING");
    }

    @Test
    @DisplayName("Redis 캐시 조회가 실패해도 gRPC 조회 결과를 반환한다")
    void getActiveCategoryCodes_whenCacheReadFails_callsGrpcAndReturnsResult() {
        when(setOperations.members(CACHE_KEY)).thenThrow(new RedisConnectionFailureException("redis unavailable"));
        when(routineStub.listCategories(any(ListCategoriesRequest.class))).thenReturn(
                ListCategoriesResponse.newBuilder()
                        .addCategories(CategoryItem.newBuilder().setCode("EXERCISE").build())
                        .addCategories(CategoryItem.newBuilder().setCode("READING").build())
                        .build()
        );

        Set<String> result = client.getActiveCategoryCodes();

        assertThat(result).containsExactlyInAnyOrder("EXERCISE", "READING");
        verify(routineStub).listCategories(any(ListCategoriesRequest.class));
    }

    @Test
    @DisplayName("Redis 캐시 저장이 실패해도 gRPC 조회 결과를 반환한다")
    void getActiveCategoryCodes_whenCacheWriteFails_returnsGrpcResult() {
        when(setOperations.members(CACHE_KEY)).thenReturn(null);
        when(routineStub.listCategories(any(ListCategoriesRequest.class))).thenReturn(
                ListCategoriesResponse.newBuilder()
                        .addCategories(CategoryItem.newBuilder().setCode("EXERCISE").build())
                        .addCategories(CategoryItem.newBuilder().setCode("READING").build())
                        .build()
        );
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(), any(), any()))
                .thenThrow(new RedisConnectionFailureException("redis unavailable"));

        Set<String> result = client.getActiveCategoryCodes();

        assertThat(result).containsExactlyInAnyOrder("EXERCISE", "READING");
        verify(routineStub).listCategories(any(ListCategoriesRequest.class));
    }

    @Test
    @DisplayName("gRPC 호출 실패 시 SERVICE_UNAVAILABLE 예외를 던진다")
    void getActiveCategoryCodes_whenGrpcFails_throwsServiceUnavailable() {
        when(setOperations.members(CACHE_KEY)).thenReturn(null);
        when(routineStub.listCategories(any(ListCategoriesRequest.class)))
                .thenThrow(Status.UNAVAILABLE.asRuntimeException());

        assertThatThrownBy(() -> client.getActiveCategoryCodes())
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.SERVICE_UNAVAILABLE));
    }
}
