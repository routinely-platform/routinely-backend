package com.routinely.routine_service.application.execution;

import com.routinely.core.exception.BusinessException;
import com.routinely.core.exception.ErrorCode;
import com.routinely.routine_service.application.event.RoutineEventPublisher;
import com.routinely.routine_service.application.execution.dto.CompleteExecutionCommand;
import com.routinely.routine_service.application.execution.dto.ExecutionCompleteResult;
import com.routinely.routine_service.application.execution.dto.ExecutionResult;
import com.routinely.routine_service.domain.definition.RoutineDefinition;
import com.routinely.routine_service.domain.execution.ExecutionStatus;
import com.routinely.routine_service.domain.execution.RoutineExecution;
import com.routinely.routine_service.domain.execution.RoutineExecutionRepository;
import com.routinely.routine_service.domain.feed.FeedCardRepository;
import com.routinely.routine_service.domain.routine.Routine;
import com.routinely.routine_service.domain.routine.RoutineRepository;
import com.routinely.routine_service.domain.template.ScheduleType;
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
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 챌린지 루틴 인증 검증 (#58) — 트랜잭션 안의 로컬 검증: 백필 창 · ended_at NULL 가드 · 완료 취소 · backfillable.
 * challenge-service 컨텍스트 확인은 트랜잭션 밖 파사드의 몫이라 RoutineExecutionCompletionServiceTest가 본다(결정 ③ C).
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

    private final RoutineExecutionService service = new RoutineExecutionServiceImpl(
            executionRepository, routineRepository, fileStorage, CLOCK, feedCardRepository,
            new AcceptedCountCalculator(executionRepository, CLOCK), eventPublisher,
            new ChallengeBackfillProperties(3));

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
    @DisplayName("백필 창 — 오늘 + 지난 3일 (결정 ②)")
    class BackfillWindow {

        @Test
        @DisplayName("챌린지루틴은_지난3일째까지_인증할수있다")
        void complete_challenge_backfillWindow_allowsThreeDaysAgo() {
            givenRoutine(challengeRoutine(ROUTINE_ID, CHALLENGE_ID, STARTED, ENDED));

            ExecutionCompleteResult result = service.completeAfterChallengeCheck(command(TODAY.minusDays(3)));

            assertThat(result.scheduledDate()).isEqualTo(TODAY.minusDays(3));
        }

        @Test
        @DisplayName("챌린지루틴은_지난4일째부터_EXECUTION_BACKFILL_WINDOW_EXCEEDED_이고_아무것도남기지않는다")
        void complete_challenge_backfillWindow_rejectsFourDaysAgo() {
            givenRoutine(challengeRoutine(ROUTINE_ID, CHALLENGE_ID, STARTED, ENDED));

            assertErrorCode(() -> service.completeAfterChallengeCheck(command(TODAY.minusDays(4))),
                    ErrorCode.EXECUTION_BACKFILL_WINDOW_EXCEEDED);
            verifyNothingPersisted();
        }

        @Test
        @DisplayName("개인루틴은_30일전도_수행기간안이면_인증할수있다")
        void complete_personal_allowsThirtyDaysAgo() {
            givenRoutine(personalRoutine(ROUTINE_ID, TODAY.minusDays(60), null));

            ExecutionCompleteResult result = service.completeAfterChallengeCheck(command(TODAY.minusDays(30)));

            assertThat(result.scheduledDate()).isEqualTo(TODAY.minusDays(30));
        }

        @Test
        @DisplayName("챌린지종료후날짜는_창안이어도_수행기간이아닙니다로거부된다")
        void complete_afterChallengeEnd_rejectedByPeriodFirst() {
            givenRoutine(challengeRoutine(ROUTINE_ID, CHALLENGE_ID, STARTED, TODAY.minusDays(2)));

            assertThatThrownBy(() -> service.completeAfterChallengeCheck(command(TODAY.minusDays(1))))
                    .isInstanceOfSatisfying(BusinessException.class, e -> {
                        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                        assertThat(e.getMessage()).isEqualTo("루틴 수행 기간이 아닙니다.");
                    });
        }
    }

    @Nested
    @DisplayName("ended_at NULL 가드")
    class EndDateGuard {

        @Test
        @DisplayName("챌린지루틴인데_ended_at이NULL이면_INTERNAL_SERVER_ERROR_이고_저장·업로드가없다")
        void complete_challengeWithoutEndDate_rejected() {
            givenRoutine(challengeRoutine(ROUTINE_ID, CHALLENGE_ID, STARTED, null));

            assertErrorCode(() -> service.completeAfterChallengeCheck(photoCommand(TODAY)), ErrorCode.INTERNAL_SERVER_ERROR);
            verifyNothingPersisted();
        }
    }

    @Nested
    @DisplayName("bulkCompleteAfterChallengeCheck · cancelComplete")
    class BulkAndCancel {

        @Test
        @DisplayName("하나라도거부되면_예외가전파되어_전체가롤백된다")
        void bulkComplete_oneRejected_propagates() {
            givenRoutine(personalRoutine(101L, STARTED, ENDED));
            givenRoutine(challengeRoutine(102L, CHALLENGE_ID, STARTED, null));

            // @Transactional 경계 안이라 앞서 저장한 개인 루틴 완료도 함께 롤백된다 (단위 테스트는 전파만 확인)
            assertErrorCode(() -> service.bulkCompleteAfterChallengeCheck(USER_ID, TODAY, List.of(101L, 102L)),
                    ErrorCode.INTERNAL_SERVER_ERROR);
        }

        @Test
        @DisplayName("완료취소는_챌린지루틴이어도_백필창이지난날짜를취소할수있다")
        void cancelComplete_challenge_outsideWindow_allowed() {
            LocalDate old = TODAY.minusDays(10);
            givenRoutine(challengeRoutine(ROUTINE_ID, CHALLENGE_ID, STARTED, ENDED));
            when(executionRepository.findByRoutineIdAndScheduledDate(ROUTINE_ID, old))
                    .thenReturn(Optional.of(RoutineExecution.completed(ROUTINE_ID, USER_ID, old, LocalDateTime.now(CLOCK))));

            ExecutionCompleteResult result = service.cancelComplete(ROUTINE_ID, USER_ID, old);

            assertThat(result.status()).isEqualTo(ExecutionStatus.PENDING);
            verify(executionRepository).delete(any(RoutineExecution.class));
        }
    }

    @Nested
    @DisplayName("조회 응답의 backfillable (결정 ⑤)")
    class Backfillable {

        private Map<LocalDate, Boolean> backfillableByDate(Routine routine, LocalDate from, LocalDate to,
                                                           List<RoutineExecution> completed) {
            when(routineRepository.findForExecutionDerivation(USER_ID, null, from, to)).thenReturn(List.of(routine));
            when(executionRepository.findCompletedInRange(USER_ID, null, from, to)).thenReturn(completed);
            return service.getMyExecutions(USER_ID, null, null, from, to).stream()
                    .collect(Collectors.toMap(ExecutionResult::scheduledDate, ExecutionResult::backfillable));
        }

        private static RoutineExecution completedOn(LocalDate date) {
            return RoutineExecution.completed(ROUTINE_ID, USER_ID, date, LocalDateTime.now(CLOCK));
        }

        @Test
        @DisplayName("챌린지루틴은_오늘·지난3일째까지true_4일째부터false_미래는false")
        void getMyExecutions_backfillable_challengeWindow() {
            Map<LocalDate, Boolean> result = backfillableByDate(
                    challengeRoutine(ROUTINE_ID, CHALLENGE_ID, STARTED, ENDED),
                    TODAY.minusDays(5), TODAY.plusDays(2), List.of());

            assertThat(result).containsEntry(TODAY.plusDays(2), false)
                    .containsEntry(TODAY.plusDays(1), false)
                    .containsEntry(TODAY, true)
                    .containsEntry(TODAY.minusDays(3), true)
                    .containsEntry(TODAY.minusDays(4), false)
                    .containsEntry(TODAY.minusDays(5), false);
        }

        @Test
        @DisplayName("COMPLETED행도_같은규칙으로계산한다_창안true·창밖false")
        void getMyExecutions_backfillable_completedRowsUseSameRule() {
            Map<LocalDate, Boolean> result = backfillableByDate(
                    challengeRoutine(ROUTINE_ID, CHALLENGE_ID, STARTED, ENDED),
                    TODAY.minusDays(5), TODAY,
                    List.of(completedOn(TODAY.minusDays(2)), completedOn(TODAY.minusDays(5))));

            assertThat(result).containsEntry(TODAY.minusDays(2), true)
                    .containsEntry(TODAY.minusDays(5), false);
        }

        @Test
        @DisplayName("개인루틴은_30일전도true_미래는false")
        void getMyExecutions_backfillable_personalUnlimited() {
            Map<LocalDate, Boolean> result = backfillableByDate(
                    personalRoutine(ROUTINE_ID, TODAY.minusDays(60), null),
                    TODAY.minusDays(30), TODAY.plusDays(1), List.of());

            assertThat(result).containsEntry(TODAY.minusDays(30), true)
                    .containsEntry(TODAY, true)
                    .containsEntry(TODAY.plusDays(1), false);
        }

        @Test
        @DisplayName("챌린지가종료되면_창안날짜도false_COMPLETED행포함")
        void getMyExecutions_backfillable_endedChallengeFalse() {
            Map<LocalDate, Boolean> result = backfillableByDate(
                    challengeRoutine(ROUTINE_ID, CHALLENGE_ID, STARTED, TODAY.minusDays(1)),
                    TODAY.minusDays(3), TODAY, List.of(completedOn(TODAY.minusDays(2))));

            assertThat(result).containsEntry(TODAY.minusDays(1), false)
                    .containsEntry(TODAY.minusDays(2), false)
                    .containsEntry(TODAY.minusDays(3), false);
        }

        @Test
        @DisplayName("종료일이오늘이면_아직진행중이라_창안true")
        void getMyExecutions_backfillable_endsTodayStillOpen() {
            Map<LocalDate, Boolean> result = backfillableByDate(
                    challengeRoutine(ROUTINE_ID, CHALLENGE_ID, STARTED, TODAY),
                    TODAY.minusDays(3), TODAY, List.of());

            assertThat(result).containsEntry(TODAY, true)
                    .containsEntry(TODAY.minusDays(3), true);
        }

        @Test
        @DisplayName("중단한루틴의_완료이력은false")
        void getMyExecutions_backfillable_inactiveRoutineFalse() {
            Routine routine = personalRoutine(ROUTINE_ID, STARTED, ENDED);
            routine.deactivate();

            Map<LocalDate, Boolean> result = backfillableByDate(routine, TODAY, TODAY, List.of(completedOn(TODAY)));

            assertThat(result).containsEntry(TODAY, false);
        }
    }
}
