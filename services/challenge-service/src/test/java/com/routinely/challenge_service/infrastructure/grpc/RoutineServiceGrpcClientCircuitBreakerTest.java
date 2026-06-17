package com.routinely.challenge_service.infrastructure.grpc;

import com.routinely.core.exception.BusinessException;
import com.routinely.proto.routine.ListCategoriesRequest;
import com.routinely.proto.routine.RoutineGrpcServiceGrpc;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.grpc.Status;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.Collections;

import static com.routinely.challenge_service.infrastructure.grpc.RoutineServiceGrpcClient.CIRCUIT_BREAKER_NAME;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = "spring.grpc.server.port=0")
@ActiveProfiles("test")
@DisplayName("RoutineServiceGrpcClient Circuit Breaker 통합")
class RoutineServiceGrpcClientCircuitBreakerTest {

    @MockitoBean
    private RoutineGrpcServiceGrpc.RoutineGrpcServiceBlockingStub routineStub;

    @MockitoBean
    private StringRedisTemplate redisTemplate;

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @Autowired
    private RoutineServiceGrpcClient client;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        circuitBreakerRegistry.circuitBreaker(CIRCUIT_BREAKER_NAME).reset();

        // 항상 캐시 미스 → 매 호출이 gRPC 경로로 진입한다.
        SetOperations<String, String> setOperations = mock(SetOperations.class);
        when(redisTemplate.opsForSet()).thenReturn(setOperations);
        when(setOperations.members(any())).thenReturn(Collections.emptySet());

        when(routineStub.withDeadlineAfter(anyLong(), any())).thenReturn(routineStub);
        when(routineStub.listCategories(any(ListCategoriesRequest.class)))
                .thenThrow(Status.UNAVAILABLE.asRuntimeException());
    }

    @Test
    @DisplayName("연속 실패가 임계치(5회)를 넘으면 서킷이 OPEN되고 이후 호출은 gRPC 없이 즉시 차단된다")
    void repeatedGrpcFailures_openCircuit_thenFastFailWithoutCallingGrpc() {
        // application.yaml: minimumNumberOfCalls=5, failureRateThreshold=50%
        // → 5회 연속 실패하면 서킷이 OPEN으로 전환된다.
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> client.getActiveCategoryCodes())
                    .isInstanceOf(BusinessException.class);
        }

        assertThat(circuitBreakerRegistry.circuitBreaker(CIRCUIT_BREAKER_NAME).getState())
                .isEqualTo(CircuitBreaker.State.OPEN);

        // OPEN 상태에서의 추가 호출도 차단되지만, 이번엔 gRPC를 호출하지 않고 즉시 fallback 된다.
        assertThatThrownBy(() -> client.getActiveCategoryCodes())
                .isInstanceOf(BusinessException.class);

        verify(routineStub, times(5)).listCategories(any(ListCategoriesRequest.class));
    }
}
