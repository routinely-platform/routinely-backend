package com.routinely.routine_service.application.execution;

import com.routinely.core.exception.BusinessException;
import com.routinely.core.exception.ErrorCode;
import com.routinely.routine_service.application.challenge.ChallengeContext;
import com.routinely.routine_service.application.execution.dto.CompleteExecutionCommand;
import com.routinely.routine_service.domain.definition.RoutineDefinition;
import com.routinely.routine_service.domain.routine.Routine;
import com.routinely.routine_service.domain.routine.RoutineRepository;
import com.routinely.routine_service.domain.template.ScheduleType;
import com.routinely.routine_service.infrastructure.grpc.ChallengeServiceGrpcClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 루틴 완료 파사드 (#58 결정 ③ C) — 트랜잭션 밖에서 challenge-service 컨텍스트를 확인하고, 통과해야만
 * 트랜잭션 서비스로 넘긴다. 거부되면 서비스를 부르지 않으므로 잠금·업로드·저장·Outbox가 모두 일어나지 않는다.
 */
@DisplayName("RoutineExecutionCompletionService — 챌린지 확인 후 완료 위임")
class RoutineExecutionCompletionServiceTest {

    private static final RoutineDefinition DEFINITION =
            RoutineDefinition.of("아침 러닝", "EXERCISE", ScheduleType.DAILY, null, null);
    private static final LocalDate TODAY = LocalDate.of(2026, 7, 24);
    private static final LocalDate STARTED = LocalDate.of(2026, 7, 1);
    private static final LocalDate ENDED = LocalDate.of(2026, 7, 31);
    private static final Long USER_ID = 1L;
    private static final Long ROUTINE_ID = 100L;
    private static final Long CHALLENGE_ID = 50L;

    private final RoutineRepository routineRepository = mock(RoutineRepository.class);
    private final ChallengeServiceGrpcClient challengeClient = mock(ChallengeServiceGrpcClient.class);
    private final RoutineExecutionService executionService = mock(RoutineExecutionService.class);

    private final RoutineExecutionCompletionService facade = new RoutineExecutionCompletionService(
            routineRepository, challengeClient, executionService, new ChallengeBackfillProperties(3));

    private static Routine personalRoutine(Long id) {
        Routine routine = Routine.forPersonal(null, USER_ID, DEFINITION, STARTED, ENDED, null);
        ReflectionTestUtils.setField(routine, "id", id);
        return routine;
    }

    private static Routine challengeRoutine(Long id, Long challengeId) {
        Routine routine = personalRoutine(id);
        ReflectionTestUtils.setField(routine, "challengeId", challengeId);
        return routine;
    }

    private void givenRoutine(Routine routine) {
        when(routineRepository.findByIdAndUserId(routine.getId(), USER_ID)).thenReturn(Optional.of(routine));
    }

    private void givenContext(Long challengeId, ChallengeContext context) {
        when(challengeClient.getChallengeContext(challengeId, USER_ID)).thenReturn(context);
    }

    private static CompleteExecutionCommand command() {
        return new CompleteExecutionCommand(ROUTINE_ID, USER_ID, TODAY, null, null, null, null);
    }

    private static void assertErrorCode(Runnable call, ErrorCode expected) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(expected));
    }

    private void verifyNotDelegated() {
        verify(executionService, never()).completeAfterChallengeCheck(any());
        verify(executionService, never()).bulkCompleteAfterChallengeCheck(any(), any(), any());
    }

    @Nested
    @DisplayName("complete")
    class Complete {

        @Test
        @DisplayName("개인루틴은_gRPC없이_서비스로넘긴다")
        void complete_personal_delegatesWithoutGrpc() {
            givenRoutine(personalRoutine(ROUTINE_ID));

            facade.complete(command());

            verifyNoInteractions(challengeClient);
            verify(executionService).completeAfterChallengeCheck(command());
        }

        @Test
        @DisplayName("루틴이없으면_gRPC없이_서비스로넘겨_ROUTINE_NOT_FOUND를맡긴다")
        void complete_routineMissing_delegatesWithoutGrpc() {
            when(routineRepository.findByIdAndUserId(ROUTINE_ID, USER_ID)).thenReturn(Optional.empty());

            facade.complete(command());

            verifyNoInteractions(challengeClient);
            verify(executionService).completeAfterChallengeCheck(command());
        }

        @Test
        @DisplayName("ACTIVE챌린지의_활성멤버면_확인후_서비스로넘긴다")
        void complete_challengeActiveMember_delegates() {
            givenRoutine(challengeRoutine(ROUTINE_ID, CHALLENGE_ID));
            givenContext(CHALLENGE_ID, ChallengeContext.of(ChallengeContext.Status.ACTIVE, true));

            facade.complete(command());

            verify(challengeClient).getChallengeContext(CHALLENGE_ID, USER_ID);
            verify(executionService).completeAfterChallengeCheck(command());
        }

        @Test
        @DisplayName("WAITING이면_409_CHALLENGE_NOT_STARTED_이고_서비스를부르지않는다")
        void complete_challengeWaiting_throwsNotStarted() {
            givenRoutine(challengeRoutine(ROUTINE_ID, CHALLENGE_ID));
            givenContext(CHALLENGE_ID, ChallengeContext.of(ChallengeContext.Status.WAITING, true));

            assertErrorCode(() -> facade.complete(command()), ErrorCode.CHALLENGE_NOT_STARTED);
            verifyNotDelegated();
        }

        @Test
        @DisplayName("ENDED면_날짜가기간·창안이어도_409_CHALLENGE_ALREADY_ENDED_이고_서비스를부르지않는다")
        void complete_challengeEnded_throwsAlreadyEnded() {
            givenRoutine(challengeRoutine(ROUTINE_ID, CHALLENGE_ID));
            givenContext(CHALLENGE_ID, ChallengeContext.of(ChallengeContext.Status.ENDED, true));

            assertErrorCode(() -> facade.complete(command()), ErrorCode.CHALLENGE_ALREADY_ENDED);
            verifyNotDelegated();
        }

        @Test
        @DisplayName("멤버가비활성이면_403_NOT_CHALLENGE_MEMBER_이고_서비스를부르지않는다")
        void complete_inactiveMember_throwsNotMember() {
            givenRoutine(challengeRoutine(ROUTINE_ID, CHALLENGE_ID));
            givenContext(CHALLENGE_ID, ChallengeContext.of(ChallengeContext.Status.ACTIVE, false));

            assertErrorCode(() -> facade.complete(command()), ErrorCode.NOT_CHALLENGE_MEMBER);
            verifyNotDelegated();
        }

        @Test
        @DisplayName("챌린지가없으면_404_CHALLENGE_NOT_FOUND_이고_서비스를부르지않는다")
        void complete_challengeNotFound_throwsNotFound() {
            givenRoutine(challengeRoutine(ROUTINE_ID, CHALLENGE_ID));
            givenContext(CHALLENGE_ID, ChallengeContext.notFound());

            assertErrorCode(() -> facade.complete(command()), ErrorCode.CHALLENGE_NOT_FOUND);
            verifyNotDelegated();
        }

        @Test
        @DisplayName("challenge-service장애면_503_SERVICE_UNAVAILABLE_로차단하고_재인증안내를담는다")
        void complete_challengeServiceDown_failsClosed() {
            givenRoutine(challengeRoutine(ROUTINE_ID, CHALLENGE_ID));
            when(challengeClient.getChallengeContext(CHALLENGE_ID, USER_ID))
                    .thenThrow(new BusinessException(ErrorCode.SERVICE_UNAVAILABLE));

            assertThatThrownBy(() -> facade.complete(command()))
                    .isInstanceOfSatisfying(BusinessException.class, e -> {
                        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE);
                        assertThat(e.getMessage()).contains("3일");
                    });
            verifyNotDelegated();
        }
    }

    @Nested
    @DisplayName("bulkComplete")
    class BulkComplete {

        @Test
        @DisplayName("개인1·챌린지2건이면_gRPC를챌린지루틴마다한번씩_2회부르고_한번에넘긴다")
        void bulkComplete_callsGrpcPerChallengeRoutine() {
            List<Long> ids = List.of(103L, 101L, 102L);
            when(routineRepository.findAllByIdInAndUserId(ids, USER_ID)).thenReturn(List.of(
                    personalRoutine(101L), challengeRoutine(102L, CHALLENGE_ID), challengeRoutine(103L, 51L)));
            givenContext(CHALLENGE_ID, ChallengeContext.of(ChallengeContext.Status.ACTIVE, true));
            givenContext(51L, ChallengeContext.of(ChallengeContext.Status.ACTIVE, true));

            facade.bulkComplete(USER_ID, TODAY, ids);

            verify(challengeClient).getChallengeContext(CHALLENGE_ID, USER_ID);
            verify(challengeClient).getChallengeContext(51L, USER_ID);
            verify(challengeClient, times(2)).getChallengeContext(anyLong(), anyLong());
            verify(executionService).bulkCompleteAfterChallengeCheck(USER_ID, TODAY, ids);
        }

        @Test
        @DisplayName("하나라도거부되면_서비스를부르지않아_아무것도남지않는다")
        void bulkComplete_oneRejected_notDelegated() {
            List<Long> ids = List.of(101L, 102L);
            when(routineRepository.findAllByIdInAndUserId(ids, USER_ID)).thenReturn(List.of(
                    personalRoutine(101L), challengeRoutine(102L, CHALLENGE_ID)));
            givenContext(CHALLENGE_ID, ChallengeContext.of(ChallengeContext.Status.ENDED, true));

            assertErrorCode(() -> facade.bulkComplete(USER_ID, TODAY, ids), ErrorCode.CHALLENGE_ALREADY_ENDED);
            verifyNotDelegated();
        }
    }
}
