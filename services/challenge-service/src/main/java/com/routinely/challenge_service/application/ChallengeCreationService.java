package com.routinely.challenge_service.application;

import com.routinely.challenge_service.application.dto.ChallengeResult;
import com.routinely.challenge_service.application.dto.CreateChallengeCommand;
import com.routinely.challenge_service.infrastructure.grpc.RoutineServiceGrpcClient;
import com.routinely.core.exception.BusinessException;
import org.springframework.stereotype.Service;

import java.util.Set;

import static com.routinely.core.exception.ErrorCode.VALIDATION_FAILED;

/**
 * 챌린지 생성 유스케이스를 오케스트레이션한다.
 *
 * <p>categoryCode 유효성 검증(routine-service gRPC 호출)을 DB 트랜잭션 <b>밖</b>에서 먼저 수행하고,
 * 통과한 경우에만 트랜잭션이 걸린 {@link ChallengeService#createChallengeAfterCategoryValidation}로 위임한다.
 * 원격 호출이 트랜잭션 내부에서 일어나 응답 대기 동안 DB 커넥션을 점유하는 것을 막기 위함이다
 * (트랜잭션 밖 → 검증, 트랜잭션 안 → 영속화).
 */
@Service
public class ChallengeCreationService {

    private final RoutineServiceGrpcClient routineServiceGrpcClient;
    private final ChallengeService challengeService;

    public ChallengeCreationService(RoutineServiceGrpcClient routineServiceGrpcClient,
                                    ChallengeService challengeService) {
        this.routineServiceGrpcClient = routineServiceGrpcClient;
        this.challengeService = challengeService;
    }

    public ChallengeResult createChallenge(Long creatorUserId, CreateChallengeCommand command) {
        validateCategoryCode(command.categoryCode());
        return challengeService.createChallengeAfterCategoryValidation(creatorUserId, command);
    }

    private void validateCategoryCode(String categoryCode) {
        Set<String> activeCodes = routineServiceGrpcClient.getActiveCategoryCodes();
        if (!activeCodes.contains(categoryCode)) {
            throw new BusinessException(VALIDATION_FAILED, "유효하지 않은 카테고리 코드입니다.");
        }
    }
}
