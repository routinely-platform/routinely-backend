package com.routinely.routine_service.application.execution;

import com.routinely.core.exception.BusinessException;
import com.routinely.core.exception.ErrorCode;
import com.routinely.routine_service.application.challenge.ChallengeContext;
import com.routinely.routine_service.application.event.RoutineEventPublisher;
import com.routinely.routine_service.application.execution.dto.CompleteExecutionCommand;
import com.routinely.routine_service.application.execution.dto.ExecutionCompleteResult;
import com.routinely.routine_service.domain.definition.RoutineDefinition;
import com.routinely.routine_service.domain.execution.ExecutionStatus;
import com.routinely.routine_service.domain.execution.RoutineExecution;
import com.routinely.routine_service.domain.execution.RoutineExecutionRepository;
import com.routinely.routine_service.domain.feed.FeedCardRepository;
import com.routinely.routine_service.domain.routine.Routine;
import com.routinely.routine_service.domain.routine.RoutineRepository;
import com.routinely.routine_service.domain.template.ScheduleType;
import com.routinely.routine_service.infrastructure.grpc.ChallengeServiceGrpcClient;
import com.routinely.storage.FileStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
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
 * 챌린지 루틴 인증 검증 (#58) — 백필 창 · ended_at NULL 가드 · challenge-service 컨텍스트 확인.
 * 검증 순서는 RoutineExecutionServiceImpl#complete 주석과 WORKPLAN 결정 ③을 따른다.
 */
@DisplayName("RoutineExecutionService — 챌린지 루틴 인증")
class RoutineExecutionChallengeTest {

    private static final RoutineDefinition DEFINITION =
            RoutineDefinition.of("아침 러닝", "EXERCISE", ScheduleType.DAILY, null, null);
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-24T00:00:00Z"), ZoneId.of("Asia/Seoul"));
    private static final LocalDate TODAY = LocalDate.of(2026, 7, 24);
    private static final LocalDate STARTED = LocalDate.of(2026, 7, 1);
    private static final LocalDate ENDED = LocalDate.of(2026, 7, 31);
    private static final Long USER_ID = 1L;
    private static final Long ROUTINE_ID = 100L;
    private static final Long CHALLENGE_ID = 50L;
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x00};

    private final RoutineExecutionRepository executionRepository = mock(RoutineExecutionRepository.class);
    private final RoutineRepository routineRepository = mock(RoutineRepository.class);
    private final FeedCardRepository feedCardRepository = mock(FeedCardRepository.class);
    private final FileStorage fileStorage = mock(FileStorage.class);
    private final RoutineEventPublisher eventPublisher = mock(RoutineEventPublisher.class);
    private final ChallengeServiceGrpcClient challengeClient = mock(ChallengeServiceGrpcClient.class);

    private final RoutineExecutionService service = new RoutineExecutionServiceImpl(
            executionRepository, routineRepository, fileStorage, CLOCK, feedCardRepository,
            new AcceptedCountCalculator(executionRepository, CLOCK), eventPublisher,
            challengeClient, new ChallengeBackfillProperties(3));

    @BeforeEach
    void setUp() {
        when(feedCardRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(executionRepository.findByRoutineIdAndScheduledDate(anyLong(), any())).thenReturn(Optional.empty());
        when(executionRepository.saveAndFlush(any(RoutineExecution.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    private static Routine personalRoutine(Long id, LocalDate started, LocalDate ended) {
        Routine routine = Routine.forPersonal(null, USER_ID, DEFINITION, started, ended, null);
        ReflectionTestUtils.setField(routine, "id", id);
        return routine;
    }

    /** 챌린지 루틴 생성은 #157(challenge.started 소비)의 몫이라 아직 팩터리가 없다 — challengeId만 심는다. */
    private static Routine challengeRoutine(Long id, Long challengeId, LocalDate started, LocalDate ended) {
        Routine routine = personalRoutine(id, started, ended);
        ReflectionTestUtils.setField(routine, "challengeId", challengeId);
        return routine;
    }

    private void givenRoutine(Routine routine) {
        when(routineRepository.findLockedByIdAndUserId(routine.getId(), USER_ID)).thenReturn(Optional.of(routine));
    }

    private void givenContext(ChallengeContext context) {
        when(challengeClient.getChallengeContext(CHALLENGE_ID, USER_ID)).thenReturn(context);
    }

    private static CompleteExecutionCommand command(LocalDate date) {
        return new CompleteExecutionCommand(ROUTINE_ID, USER_ID, date, null, null, null, null);
    }

    private static CompleteExecutionCommand photoCommand(LocalDate date) {
        return new CompleteExecutionCommand(ROUTINE_ID, USER_ID, date, "photo.jpg", "image/jpeg", JPEG, null);
    }

    private static void assertErrorCode(Runnable call, ErrorCode expected) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(expected));
    }

    /** 거부된 요청은 사진·실행 기록·Outbox 어디에도 흔적을 남기지 않는다. */
    private void verifyNothingPersisted() {
        verify(fileStorage, never()).upload(any());
        verify(executionRepository, never()).saveAndFlush(any());
        verify(feedCardRepository, never()).save(any());
        verify(eventPublisher, never()).publishCompleted(any(), any(), any(), any());
    }

    @Nested
    @DisplayName("challenge-service 컨텍스트 확인")
    class ChallengeContextCheck {

        @Test
        @DisplayName("개인루틴은_gRPC를호출하지않는다")
        void complete_personal_doesNotCallGrpc() {
            givenRoutine(personalRoutine(ROUTINE_ID, STARTED, ENDED));

            service.complete(command(TODAY));

            verifyNoInteractions(challengeClient);
        }

        @Test
        @DisplayName("ACTIVE챌린지의_활성멤버면_완료하고_이벤트를발행한다")
        void complete_challengeActiveMember_success() {
            givenRoutine(challengeRoutine(ROUTINE_ID, CHALLENGE_ID, STARTED, ENDED));
            givenContext(ChallengeContext.of(ChallengeContext.Status.ACTIVE, true));

            ExecutionCompleteResult result = service.complete(command(TODAY));

            assertThat(result.status()).isEqualTo(ExecutionStatus.COMPLETED);
            verify(challengeClient).getChallengeContext(CHALLENGE_ID, USER_ID);
            verify(eventPublisher).publishCompleted(any(), any(), any(), any());
        }

        @Test
        @DisplayName("WAITING이면_409_CHALLENGE_NOT_STARTED_이고_아무것도남기지않는다")
        void complete_challengeWaiting_throwsNotStarted() {
            givenRoutine(challengeRoutine(ROUTINE_ID, CHALLENGE_ID, STARTED, ENDED));
            givenContext(ChallengeContext.of(ChallengeContext.Status.WAITING, true));

            assertErrorCode(() -> service.complete(photoCommand(TODAY)), ErrorCode.CHALLENGE_NOT_STARTED);
            verifyNothingPersisted();
        }

        @Test
        @DisplayName("ENDED면_409_CHALLENGE_ALREADY_ENDED_이고_아무것도남기지않는다")
        void complete_challengeEnded_throwsAlreadyEnded() {
            givenRoutine(challengeRoutine(ROUTINE_ID, CHALLENGE_ID, STARTED, ENDED));
            givenContext(ChallengeContext.of(ChallengeContext.Status.ENDED, true));

            assertErrorCode(() -> service.complete(photoCommand(TODAY)), ErrorCode.CHALLENGE_ALREADY_ENDED);
            verifyNothingPersisted();
        }

        @Test
        @DisplayName("멤버가비활성이면_403_NOT_CHALLENGE_MEMBER_이고_아무것도남기지않는다")
        void complete_inactiveMember_throwsNotMember() {
            givenRoutine(challengeRoutine(ROUTINE_ID, CHALLENGE_ID, STARTED, ENDED));
            givenContext(ChallengeContext.of(ChallengeContext.Status.ACTIVE, false));

            assertErrorCode(() -> service.complete(photoCommand(TODAY)), ErrorCode.NOT_CHALLENGE_MEMBER);
            verifyNothingPersisted();
        }

        @Test
        @DisplayName("챌린지가없으면_404_CHALLENGE_NOT_FOUND")
        void complete_challengeNotFound_throwsNotFound() {
            givenRoutine(challengeRoutine(ROUTINE_ID, CHALLENGE_ID, STARTED, ENDED));
            givenContext(ChallengeContext.notFound());

            assertErrorCode(() -> service.complete(photoCommand(TODAY)), ErrorCode.CHALLENGE_NOT_FOUND);
            verifyNothingPersisted();
        }

        @Test
        @DisplayName("challenge-service장애면_503_SERVICE_UNAVAILABLE_로차단하고_재인증안내를담는다")
        void complete_challengeServiceDown_failsClosed() {
            givenRoutine(challengeRoutine(ROUTINE_ID, CHALLENGE_ID, STARTED, ENDED));
            when(challengeClient.getChallengeContext(CHALLENGE_ID, USER_ID))
                    .thenThrow(new BusinessException(ErrorCode.SERVICE_UNAVAILABLE));

            assertThatThrownBy(() -> service.complete(photoCommand(TODAY)))
                    .isInstanceOfSatisfying(BusinessException.class, e -> {
                        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE);
                        assertThat(e.getMessage()).contains("3일");
                    });
            verifyNothingPersisted();
        }

        @Test
        @DisplayName("이미완료한날의재요청은_gRPC없이_409로끝난다")
        void complete_alreadyCompleted_doesNotCallGrpc() {
            givenRoutine(challengeRoutine(ROUTINE_ID, CHALLENGE_ID, STARTED, ENDED));
            when(executionRepository.findByRoutineIdAndScheduledDate(ROUTINE_ID, TODAY))
                    .thenReturn(Optional.of(mock(RoutineExecution.class)));

            assertErrorCode(() -> service.complete(command(TODAY)), ErrorCode.EXECUTION_ALREADY_COMPLETED);
            verifyNoInteractions(challengeClient);
        }

        @Test
        @DisplayName("챌린지가어제종료됐고_그저께를백필하면_기간·창안이어도_ENDED로거부된다")
        void complete_endedYesterday_backfillDayBefore_rejectedByGrpc() {
            givenRoutine(challengeRoutine(ROUTINE_ID, CHALLENGE_ID, STARTED, TODAY.minusDays(1)));
            givenContext(ChallengeContext.of(ChallengeContext.Status.ENDED, true));

            assertErrorCode(() -> service.complete(command(TODAY.minusDays(2))), ErrorCode.CHALLENGE_ALREADY_ENDED);
            verify(challengeClient).getChallengeContext(CHALLENGE_ID, USER_ID);
        }
    }

    @Nested
    @DisplayName("백필 창 — 오늘 + 지난 3일 (결정 ②)")
    class BackfillWindow {

        @Test
        @DisplayName("챌린지루틴은_지난3일째까지_인증할수있다")
        void complete_challenge_backfillWindow_allowsThreeDaysAgo() {
            givenRoutine(challengeRoutine(ROUTINE_ID, CHALLENGE_ID, STARTED, ENDED));
            givenContext(ChallengeContext.of(ChallengeContext.Status.ACTIVE, true));

            ExecutionCompleteResult result = service.complete(command(TODAY.minusDays(3)));

            assertThat(result.scheduledDate()).isEqualTo(TODAY.minusDays(3));
        }

        @Test
        @DisplayName("챌린지루틴은_지난4일째부터_EXECUTION_BACKFILL_WINDOW_EXCEEDED_이고_gRPC를부르지않는다")
        void complete_challenge_backfillWindow_rejectsFourDaysAgo() {
            givenRoutine(challengeRoutine(ROUTINE_ID, CHALLENGE_ID, STARTED, ENDED));

            assertErrorCode(() -> service.complete(command(TODAY.minusDays(4))),
                    ErrorCode.EXECUTION_BACKFILL_WINDOW_EXCEEDED);
            verifyNoInteractions(challengeClient);
            verifyNothingPersisted();
        }

        @Test
        @DisplayName("개인루틴은_30일전도_수행기간안이면_인증할수있다")
        void complete_personal_allowsThirtyDaysAgo() {
            givenRoutine(personalRoutine(ROUTINE_ID, TODAY.minusDays(60), null));

            ExecutionCompleteResult result = service.complete(command(TODAY.minusDays(30)));

            assertThat(result.scheduledDate()).isEqualTo(TODAY.minusDays(30));
        }

        @Test
        @DisplayName("챌린지종료후날짜는_창안이어도_수행기간이아닙니다로거부된다")
        void complete_afterChallengeEnd_rejectedByPeriodFirst() {
            givenRoutine(challengeRoutine(ROUTINE_ID, CHALLENGE_ID, STARTED, TODAY.minusDays(2)));

            assertThatThrownBy(() -> service.complete(command(TODAY.minusDays(1))))
                    .isInstanceOfSatisfying(BusinessException.class, e -> {
                        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                        assertThat(e.getMessage()).isEqualTo("루틴 수행 기간이 아닙니다.");
                    });
            verifyNoInteractions(challengeClient);
        }
    }

    @Nested
    @DisplayName("ended_at NULL 가드")
    class EndDateGuard {

        @Test
        @DisplayName("챌린지루틴인데_ended_at이NULL이면_INTERNAL_SERVER_ERROR_이고_저장·업로드·gRPC가없다")
        void complete_challengeWithoutEndDate_rejected() {
            givenRoutine(challengeRoutine(ROUTINE_ID, CHALLENGE_ID, STARTED, null));

            assertErrorCode(() -> service.complete(photoCommand(TODAY)), ErrorCode.INTERNAL_SERVER_ERROR);
            verifyNothingPersisted();
            verifyNoInteractions(challengeClient);
        }
    }

    @Nested
    @DisplayName("bulkComplete · cancelComplete")
    class BulkAndCancel {

        @Test
        @DisplayName("개인1·챌린지2건이면_gRPC를챌린지루틴마다한번씩_2회부른다")
        void bulkComplete_callsGrpcPerChallengeRoutine() {
            givenRoutine(personalRoutine(101L, STARTED, ENDED));
            givenRoutine(challengeRoutine(102L, CHALLENGE_ID, STARTED, ENDED));
            givenRoutine(challengeRoutine(103L, 51L, STARTED, ENDED));
            givenContext(ChallengeContext.of(ChallengeContext.Status.ACTIVE, true));
            when(challengeClient.getChallengeContext(51L, USER_ID))
                    .thenReturn(ChallengeContext.of(ChallengeContext.Status.ACTIVE, true));

            List<ExecutionCompleteResult> results = service.bulkComplete(USER_ID, TODAY, List.of(103L, 101L, 102L));

            assertThat(results).hasSize(3);
            verify(challengeClient).getChallengeContext(CHALLENGE_ID, USER_ID);
            verify(challengeClient).getChallengeContext(51L, USER_ID);
            verify(challengeClient, times(2)).getChallengeContext(anyLong(), anyLong());
        }

        @Test
        @DisplayName("하나라도거부되면_예외가전파되어_전체가롤백된다")
        void bulkComplete_oneRejected_propagates() {
            givenRoutine(personalRoutine(101L, STARTED, ENDED));
            givenRoutine(challengeRoutine(102L, CHALLENGE_ID, STARTED, ENDED));
            givenContext(ChallengeContext.of(ChallengeContext.Status.ENDED, true));

            // @Transactional 경계 안이라 앞서 저장한 개인 루틴 완료도 함께 롤백된다 (단위 테스트는 전파만 확인)
            assertErrorCode(() -> service.bulkComplete(USER_ID, TODAY, List.of(101L, 102L)),
                    ErrorCode.CHALLENGE_ALREADY_ENDED);
        }

        @Test
        @DisplayName("완료취소는_챌린지루틴이어도_gRPC를부르지않는다")
        void cancelComplete_challenge_doesNotCallGrpc() {
            givenRoutine(challengeRoutine(ROUTINE_ID, CHALLENGE_ID, STARTED, ENDED));
            when(executionRepository.findByRoutineIdAndScheduledDate(ROUTINE_ID, TODAY))
                    .thenReturn(Optional.of(RoutineExecution.completed(ROUTINE_ID, USER_ID, TODAY, LocalDateTime.now(CLOCK))));

            service.cancelComplete(ROUTINE_ID, USER_ID, TODAY);

            verifyNoInteractions(challengeClient);
        }
    }
}
