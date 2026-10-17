package com.routinely.routine_service.infrastructure.grpc;

import com.routinely.core.exception.BusinessException;
import com.routinely.core.exception.ErrorCode;
import com.routinely.proto.challenge.ChallengeGrpcServiceGrpc;
import com.routinely.proto.challenge.ChallengeLifecycleStatus;
import com.routinely.proto.challenge.GetChallengeContextRequest;
import com.routinely.proto.challenge.GetChallengeContextResponse;
import com.routinely.routine_service.application.challenge.ChallengeContext;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.grpc.Status;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.cloud.client.circuitbreaker.CircuitBreaker;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;

import java.time.Duration;
import java.util.function.Function;
import java.util.function.Supplier;

import static com.routinely.routine_service.infrastructure.grpc.ChallengeServiceGrpcClient.CIRCUIT_BREAKER_NAME;
import static com.routinely.routine_service.infrastructure.grpc.ChallengeServiceGrpcClient.GRPC_DEADLINE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("ChallengeServiceGrpcClient")
class ChallengeServiceGrpcClientTest {

    private static final Long CHALLENGE_ID = 10L;
    private static final Long USER_ID = 1L;

    private ChallengeGrpcServiceGrpc.ChallengeGrpcServiceBlockingStub challengeStub;

    @BeforeEach
    void setUpStub() {
        challengeStub = mock(ChallengeGrpcServiceGrpc.ChallengeGrpcServiceBlockingStub.class);
        when(challengeStub.withDeadlineAfter(anyLong(), any())).thenReturn(challengeStub);
    }

    private static GetChallengeContextResponse response(ChallengeLifecycleStatus status, boolean memberActive) {
        return GetChallengeContextResponse.newBuilder()
                .setChallengeStatus(status)
                .setIsMemberActive(memberActive)
                .build();
    }

    private static void assertServiceUnavailable(Runnable call) {
        assertThatThrownBy(call::run)
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.SERVICE_UNAVAILABLE));
    }

    @Nested
    @DisplayName("응답 변환")
    class Mapping {

        private ChallengeServiceGrpcClient client;

        @BeforeEach
        @SuppressWarnings("unchecked")
        void setUp() {
            // 서킷 브레이커는 supplier를 실행하고, 예외가 나면 fallback으로 위임하는 pass-through로 대체한다.
            CircuitBreakerFactory<?, ?> circuitBreakerFactory = mock(CircuitBreakerFactory.class);
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
            client = new ChallengeServiceGrpcClient(challengeStub, circuitBreakerFactory);
        }

        @Test
        @DisplayName("challengeId·userId를 담아 deadline을 걸고 호출하며, 상태와 멤버 활성 여부를 그대로 옮긴다")
        void getChallengeContext_success_mapsResponse() {
            when(challengeStub.getChallengeContext(any(GetChallengeContextRequest.class)))
                    .thenReturn(response(ChallengeLifecycleStatus.ACTIVE, true));

            ChallengeContext result = client.getChallengeContext(CHALLENGE_ID, USER_ID);

            assertThat(result).isEqualTo(ChallengeContext.of(ChallengeContext.Status.ACTIVE, true));
            ArgumentCaptor<GetChallengeContextRequest> captor = ArgumentCaptor.forClass(GetChallengeContextRequest.class);
            verify(challengeStub).getChallengeContext(captor.capture());
            assertThat(captor.getValue().getChallengeId()).isEqualTo(CHALLENGE_ID);
            assertThat(captor.getValue().getUserId()).isEqualTo(USER_ID);
            verify(challengeStub).withDeadlineAfter(eq(GRPC_DEADLINE.toMillis()), any());
        }

        @Test
        @DisplayName("WAITING·ENDED와 비활성 멤버도 그대로 옮긴다")
        void getChallengeContext_waitingOrEnded_mapsResponse() {
            when(challengeStub.getChallengeContext(any(GetChallengeContextRequest.class)))
                    .thenReturn(response(ChallengeLifecycleStatus.WAITING, true))
                    .thenReturn(response(ChallengeLifecycleStatus.ENDED, false));

            assertThat(client.getChallengeContext(CHALLENGE_ID, USER_ID))
                    .isEqualTo(ChallengeContext.of(ChallengeContext.Status.WAITING, true));
            assertThat(client.getChallengeContext(CHALLENGE_ID, USER_ID))
                    .isEqualTo(ChallengeContext.of(ChallengeContext.Status.ENDED, false));
        }

        @Test
        @DisplayName("NOT_FOUND는 장애가 아니라 '없음' 결과로 바꾼다")
        void getChallengeContext_notFound_returnsNotFound() {
            when(challengeStub.getChallengeContext(any(GetChallengeContextRequest.class)))
                    .thenThrow(Status.NOT_FOUND.withDescription("CHALLENGE_NOT_FOUND").asRuntimeException());

            ChallengeContext result = client.getChallengeContext(CHALLENGE_ID, USER_ID);

            assertThat(result.exists()).isFalse();
        }

        @Test
        @DisplayName("UNAVAILABLE·deadline 초과는 SERVICE_UNAVAILABLE로 차단한다 (Fail Closed)")
        void getChallengeContext_grpcFailure_throwsServiceUnavailable() {
            when(challengeStub.getChallengeContext(any(GetChallengeContextRequest.class)))
                    .thenThrow(Status.UNAVAILABLE.asRuntimeException())
                    .thenThrow(Status.DEADLINE_EXCEEDED.asRuntimeException());

            assertServiceUnavailable(() -> client.getChallengeContext(CHALLENGE_ID, USER_ID));
            assertServiceUnavailable(() -> client.getChallengeContext(CHALLENGE_ID, USER_ID));
        }

        @Test
        @DisplayName("상태가 비어 있으면(UNSPECIFIED) 판단 근거가 없으므로 SERVICE_UNAVAILABLE로 차단한다")
        void getChallengeContext_unspecifiedStatus_throwsServiceUnavailable() {
            when(challengeStub.getChallengeContext(any(GetChallengeContextRequest.class)))
                    .thenReturn(response(ChallengeLifecycleStatus.CHALLENGE_LIFECYCLE_STATUS_UNSPECIFIED, true));

            assertServiceUnavailable(() -> client.getChallengeContext(CHALLENGE_ID, USER_ID));
        }
    }

    /**
     * 실제 resilience4j 서킷으로 실패 집계를 확인한다. application.yaml의 challenge-service-cb와 같은 값을 쓴다.
     * Spring Cloud의 Resilience4JCircuitBreaker처럼 supplier가 던진 예외만 실패로 세고 fallback으로 넘긴다.
     */
    @Nested
    @DisplayName("서킷 브레이커 집계")
    class CircuitBreakerCounting {

        private io.github.resilience4j.circuitbreaker.CircuitBreaker r4jCircuitBreaker;
        private ChallengeServiceGrpcClient client;

        @BeforeEach
        @SuppressWarnings("unchecked")
        void setUp() {
            CircuitBreakerRegistry registry = CircuitBreakerRegistry.of(CircuitBreakerConfig.custom()
                    .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                    .slidingWindowSize(10)
                    .minimumNumberOfCalls(5)
                    .failureRateThreshold(50)
                    .waitDurationInOpenState(Duration.ofSeconds(10))
                    .build());
            r4jCircuitBreaker = registry.circuitBreaker(CIRCUIT_BREAKER_NAME);

            CircuitBreakerFactory<?, ?> circuitBreakerFactory = mock(CircuitBreakerFactory.class);
            CircuitBreaker circuitBreaker = mock(CircuitBreaker.class);
            when(circuitBreakerFactory.create(CIRCUIT_BREAKER_NAME)).thenReturn(circuitBreaker);
            when(circuitBreaker.run(any(Supplier.class), any(Function.class))).thenAnswer(invocation -> {
                Supplier<Object> toRun = invocation.getArgument(0);
                Function<Throwable, Object> fallback = invocation.getArgument(1);
                try {
                    return r4jCircuitBreaker.executeSupplier(toRun);
                } catch (Throwable t) {
                    return fallback.apply(t);
                }
            });
            client = new ChallengeServiceGrpcClient(challengeStub, circuitBreakerFactory);
        }

        @Test
        @DisplayName("NOT_FOUND가 계속 와도 서킷 실패로 세지 않아 CLOSED를 유지한다")
        void notFound_isNotCountedAsFailure() {
            when(challengeStub.getChallengeContext(any(GetChallengeContextRequest.class)))
                    .thenThrow(Status.NOT_FOUND.asRuntimeException());

            for (int i = 0; i < 10; i++) {
                assertThat(client.getChallengeContext(CHALLENGE_ID, USER_ID).exists()).isFalse();
            }

            assertThat(r4jCircuitBreaker.getState())
                    .isEqualTo(io.github.resilience4j.circuitbreaker.CircuitBreaker.State.CLOSED);
            assertThat(r4jCircuitBreaker.getMetrics().getNumberOfFailedCalls()).isZero();
        }

        @Test
        @DisplayName("연속 실패 5회면 서킷이 OPEN되고, 이후 호출은 gRPC 없이 SERVICE_UNAVAILABLE로 차단된다")
        void repeatedFailures_openCircuit_thenFastFail() {
            when(challengeStub.getChallengeContext(any(GetChallengeContextRequest.class)))
                    .thenThrow(Status.UNAVAILABLE.asRuntimeException());

            for (int i = 0; i < 5; i++) {
                assertServiceUnavailable(() -> client.getChallengeContext(CHALLENGE_ID, USER_ID));
            }
            assertThat(r4jCircuitBreaker.getState())
                    .isEqualTo(io.github.resilience4j.circuitbreaker.CircuitBreaker.State.OPEN);

            assertServiceUnavailable(() -> client.getChallengeContext(CHALLENGE_ID, USER_ID));
            verify(challengeStub, times(5)).getChallengeContext(any(GetChallengeContextRequest.class));
        }
    }
}
