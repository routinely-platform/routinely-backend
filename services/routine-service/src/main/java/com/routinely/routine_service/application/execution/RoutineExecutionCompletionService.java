package com.routinely.routine_service.application.execution;

import com.routinely.core.exception.BusinessException;
import com.routinely.core.exception.ErrorCode;
import com.routinely.routine_service.application.challenge.ChallengeContext;
import com.routinely.routine_service.application.execution.dto.CompleteExecutionCommand;
import com.routinely.routine_service.application.execution.dto.ExecutionCompleteResult;
import com.routinely.routine_service.domain.routine.Routine;
import com.routinely.routine_service.domain.routine.RoutineRepository;
import com.routinely.routine_service.infrastructure.grpc.ChallengeServiceGrpcClient;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 루틴 완료 유스케이스를 오케스트레이션한다 (#58 결정 ③ C).
 *
 * <p>챌린지 루틴이면 challenge-service {@code GetChallengeContext}로 챌린지가 ACTIVE이고 사용자가 활성 멤버인지를
 * DB 트랜잭션 <b>밖</b>에서 먼저 확인하고(Fail Closed), 통과한 경우에만 트랜잭션이 걸린
 * {@link RoutineExecutionService#completeAfterChallengeCheck}로 위임한다. 원격 응답을 기다리는 동안 DB 커넥션과
 * 루틴 잠금을 쥐지 않기 위함이다 — 챌린지 생성의 {@code ChallengeCreationService}와 같은 구조다
 * (트랜잭션 밖 → 원격 검증, 트랜잭션 안 → 잠금·로컬 검증·영속화).
 *
 * <p>루틴은 잠금 없이 읽는다. 챌린지 루틴인지만 알면 되고, 날짜·백필 창·중복·중단 검사는 서비스가 잠금 뒤 최신
 * 상태로 다시 한다. 확인과 잠금 사이에 사용자가 탈퇴하면 그 한 건은 통과한다 — 원격 확인을 트랜잭션 밖으로 뺀 대가로
 * 받아들인다.
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class RoutineExecutionCompletionService {

    private final RoutineRepository routineRepository;
    private final ChallengeServiceGrpcClient challengeClient;
    private final RoutineExecutionService executionService;
    private final ChallengeBackfillProperties backfillProperties;

    /** 루틴이 없으면 그대로 넘긴다 — ROUTINE_NOT_FOUND는 서비스가 잠금 조회에서 던진다. */
    public ExecutionCompleteResult complete(CompleteExecutionCommand command) {
        routineRepository.findByIdAndUserId(command.routineId(), command.userId())
                .filter(Routine::isChallengeRoutine)
                .ifPresent(routine -> ensureChallengeOpen(routine, command.userId()));
        return executionService.completeAfterChallengeCheck(command);
    }

    /**
     * 챌린지 루틴마다 한 번씩 확인한 뒤 일괄 완료로 넘긴다(결정 ④). 하나라도 거부되면 서비스를 부르지 않아 아무것도
     * 남지 않는다. 사용자는 챌린지당 루틴이 하나라 같은 챌린지를 두 번 묻는 일은 없다.
     */
    public List<ExecutionCompleteResult> bulkComplete(Long userId, LocalDate date, List<Long> routineIds) {
        routineRepository.findAllByIdInAndUserId(routineIds, userId).stream()
                .filter(Routine::isChallengeRoutine)
                .sorted(Comparator.comparing(Routine::getId))
                .forEach(routine -> ensureChallengeOpen(routine, userId));
        return executionService.bulkCompleteAfterChallengeCheck(userId, date, routineIds);
    }

    /**
     * challenge-service에 챌린지 상태와 멤버 활성 여부를 묻고 #58 결정 ① 표대로 거부한다.
     * 장애·서킷 OPEN은 클라이언트가 SERVICE_UNAVAILABLE로 끊는다(Fail Closed) — 여기서는 안내 문구만 바꾼다.
     */
    private void ensureChallengeOpen(Routine routine, Long userId) {
        ChallengeContext context;
        try {
            context = challengeClient.getChallengeContext(routine.getChallengeId(), userId);
        } catch (BusinessException e) {
            if (e.getErrorCode() == ErrorCode.SERVICE_UNAVAILABLE) {
                throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, String.format(
                        "일시적인 서버 문제로 인증할 수 없습니다. 지난 날짜는 %d일 안에 다시 인증할 수 있습니다.",
                        backfillProperties.backfillDays()), e);
            }
            throw e;
        }

        if (!context.exists()) {
            // 루틴은 있는데 챌린지가 없다 — 데이터 이상이므로 크게 남긴다.
            log.error("챌린지 루틴이 가리키는 챌린지가 없습니다. routineId={}, challengeId={}",
                    routine.getId(), routine.getChallengeId());
            throw new BusinessException(ErrorCode.CHALLENGE_NOT_FOUND);
        }
        switch (context.status()) {
            case WAITING -> throw new BusinessException(ErrorCode.CHALLENGE_NOT_STARTED);
            case ENDED -> throw new BusinessException(ErrorCode.CHALLENGE_ALREADY_ENDED);
            case ACTIVE -> {
            }
        }
        if (!context.memberActive()) {
            throw new BusinessException(ErrorCode.NOT_CHALLENGE_MEMBER);
        }
    }
}
