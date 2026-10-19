package com.routinely.routine_service.infrastructure.grpc;

import com.routinely.core.exception.BusinessException;
import com.routinely.proto.challenge.ChallengeGrpcServiceGrpc;
import com.routinely.proto.challenge.GetChallengeContextRequest;
import com.routinely.proto.challenge.GetChallengeContextResponse;
import com.routinely.routine_service.application.challenge.ChallengeContext;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static com.routinely.core.exception.ErrorCode.SERVICE_UNAVAILABLE;

@Slf4j
@Component
public class ChallengeServiceGrpcClient {

    static final Duration GRPC_DEADLINE = Duration.ofSeconds(3);
    static final String CIRCUIT_BREAKER_NAME = "challenge-service-cb";

    private final ChallengeGrpcServiceGrpc.ChallengeGrpcServiceBlockingStub challengeStub;
    private final CircuitBreakerFactory<?, ?> circuitBreakerFactory;

    public ChallengeServiceGrpcClient(ChallengeGrpcServiceGrpc.ChallengeGrpcServiceBlockingStub challengeStub,
                                      CircuitBreakerFactory<?, ?> circuitBreakerFactory) {
        this.challengeStub = challengeStub;
        this.circuitBreakerFactory = circuitBreakerFactory;
    }

    /**
     * 챌린지 진행 상태와 사용자의 멤버 활성 여부를 조회한다. (#58)
     * deadline + circuit breaker로 감싸며, 호출 실패·서킷 OPEN 시 인증을 차단한다 (Fail Closed).
     *
     * <p>{@code NOT_FOUND}는 challenge-service가 정상 동작한 결과이므로 supplier 안에서 잡아
     * {@link ChallengeContext#notFound()}로 바꾼다. 밖으로 던지면 서킷이 장애로 세어 503이 된다.
     */
    public ChallengeContext getChallengeContext(Long challengeId, Long userId) {
        return circuitBreakerFactory.create(CIRCUIT_BREAKER_NAME)
                .run(() -> fetchChallengeContext(challengeId, userId), this::failClosed);
    }

    private ChallengeContext fetchChallengeContext(Long challengeId, Long userId) {
        GetChallengeContextResponse response;
        try {
            response = challengeStub
                    .withDeadlineAfter(GRPC_DEADLINE.toMillis(), TimeUnit.MILLISECONDS)
                    .getChallengeContext(GetChallengeContextRequest.newBuilder()
                            .setChallengeId(challengeId)
                            .setUserId(userId)
                            .build());
        } catch (StatusRuntimeException e) {
            if (e.getStatus().getCode() == Status.Code.NOT_FOUND) {
                return ChallengeContext.notFound();
            }
            throw e;
        }
        return ChallengeContext.of(toStatus(response), response.getIsMemberActive());
    }

    private ChallengeContext.Status toStatus(GetChallengeContextResponse response) {
        return switch (response.getChallengeStatus()) {
            case WAITING -> ChallengeContext.Status.WAITING;
            case ACTIVE -> ChallengeContext.Status.ACTIVE;
            case ENDED -> ChallengeContext.Status.ENDED;
            // 서버가 상태를 비워 보냈거나 모르는 값이면 판단할 근거가 없다 — 장애로 보고 차단한다.
            case CHALLENGE_LIFECYCLE_STATUS_UNSPECIFIED, UNRECOGNIZED -> throw new IllegalStateException(
                    "알 수 없는 챌린지 상태: " + response.getChallengeStatusValue());
        };
    }

    private ChallengeContext failClosed(Throwable throwable) {
        log.error("challenge-service 챌린지 컨텍스트 조회 실패 — 루틴 인증 차단 (Fail Closed)", throwable);
        throw new BusinessException(SERVICE_UNAVAILABLE);
    }
}
