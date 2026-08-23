package com.routinely.routine_service.application.execution;

import com.routinely.core.exception.BusinessException;
import com.routinely.core.exception.ErrorCode;
import com.routinely.routine_service.application.execution.dto.CompleteExecutionCommand;
import com.routinely.routine_service.application.execution.dto.ExecutionCompleteResult;
import com.routinely.routine_service.application.execution.dto.ExecutionResult;
import com.routinely.routine_service.domain.execution.ExecutionStatus;
import com.routinely.routine_service.domain.execution.RoutineExecution;
import com.routinely.routine_service.domain.execution.RoutineExecutionRepository;
import com.routinely.routine_service.domain.routine.Routine;
import com.routinely.routine_service.domain.routine.RoutineRepository;
import com.routinely.routine_service.domain.template.RoutineTemplate;
import com.routinely.routine_service.domain.template.RoutineTemplateRepository;
import com.routinely.routine_service.domain.template.ScheduleType;
import com.routinely.storage.FileStorage;
import com.routinely.storage.StoredFile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("RoutineExecutionService")
class RoutineExecutionServiceTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-24T00:00:00Z"), ZONE);
    private static final LocalDate TODAY = LocalDate.of(2026, 7, 24);
    private static final Long USER_ID = 1L;
    private static final Long ROUTINE_ID = 100L;
    private static final Long TEMPLATE_ID = 10L;

    private final RoutineExecutionRepository executionRepository = mock(RoutineExecutionRepository.class);
    private final RoutineRepository routineRepository = mock(RoutineRepository.class);
    private final RoutineTemplateRepository templateRepository = mock(RoutineTemplateRepository.class);
    private final FileStorage fileStorage = mock(FileStorage.class);

    private final RoutineExecutionService service = new RoutineExecutionService(
            executionRepository, routineRepository, templateRepository, fileStorage, CLOCK);

    private static Routine routine(LocalDate started, LocalDate ended) {
        Routine routine = Routine.forPersonal(TEMPLATE_ID, USER_ID, started, ended, null);
        ReflectionTestUtils.setField(routine, "id", ROUTINE_ID);
        return routine;
    }

    private static Routine inactiveRoutine(LocalDate started, LocalDate ended) {
        Routine routine = routine(started, ended);
        routine.deactivate();
        return routine;
    }

    private static RoutineTemplate template(ScheduleType type, Short days, Integer count) {
        RoutineTemplate template = RoutineTemplate.forPersonal(USER_ID, "아침 러닝", "EXERCISE", type, days, count);
        ReflectionTestUtils.setField(template, "id", TEMPLATE_ID);
        return template;
    }

    private CompleteExecutionCommand command(LocalDate date, byte[] photo, String contentType, String memo) {
        return new CompleteExecutionCommand(ROUTINE_ID, USER_ID, date,
                photo == null ? null : "photo.jpg", contentType, photo, memo);
    }

    @Nested
    @DisplayName("complete")
    class Complete {

        @Test
        @DisplayName("오늘예정_DAILY루틴을_사진없이완료하면_COMPLETED결과를반환한다")
        void complete_daily_withoutPhoto_success() {
            when(routineRepository.findByIdAndUserId(ROUTINE_ID, USER_ID))
                    .thenReturn(Optional.of(routine(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 8, 1))));
            when(templateRepository.findById(TEMPLATE_ID))
                    .thenReturn(Optional.of(template(ScheduleType.DAILY, null, null)));
            when(executionRepository.findByRoutineIdAndScheduledDate(ROUTINE_ID, TODAY))
                    .thenReturn(Optional.empty());
            when(executionRepository.saveAndFlush(any(RoutineExecution.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            ExecutionCompleteResult result = service.complete(command(TODAY, null, null, "완료!"));

            assertThat(result.status()).isEqualTo(ExecutionStatus.COMPLETED);
            assertThat(result.scheduledDate()).isEqualTo(TODAY);
            assertThat(result.completedAt()).isEqualTo(LocalDateTime.now(CLOCK));
            verify(fileStorage, never()).upload(any());
        }

        @Test
        @DisplayName("이미완료된날이면_409_EXECUTION_ALREADY_COMPLETED")
        void complete_whenAlreadyCompleted_throwsConflict() {
            when(routineRepository.findByIdAndUserId(ROUTINE_ID, USER_ID))
                    .thenReturn(Optional.of(routine(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 8, 1))));
            when(templateRepository.findById(TEMPLATE_ID))
                    .thenReturn(Optional.of(template(ScheduleType.DAILY, null, null)));
            when(executionRepository.findByRoutineIdAndScheduledDate(ROUTINE_ID, TODAY))
                    .thenReturn(Optional.of(mock(RoutineExecution.class)));

            assertThatThrownBy(() -> service.complete(command(TODAY, null, null, null)))
                    .isInstanceOfSatisfying(BusinessException.class, e ->
                            assertThat(e.getErrorCode()).isEqualTo(ErrorCode.EXECUTION_ALREADY_COMPLETED));
            verify(executionRepository, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("지난날짜도_수행기간내면_백필로완료할수있다")
        void complete_pastDate_backfill_success() {
            LocalDate past = TODAY.minusDays(3);
            when(routineRepository.findByIdAndUserId(ROUTINE_ID, USER_ID))
                    .thenReturn(Optional.of(routine(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 8, 1))));
            when(templateRepository.findById(TEMPLATE_ID))
                    .thenReturn(Optional.of(template(ScheduleType.DAILY, null, null)));
            when(executionRepository.findByRoutineIdAndScheduledDate(ROUTINE_ID, past))
                    .thenReturn(Optional.empty());
            when(executionRepository.saveAndFlush(any(RoutineExecution.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            ExecutionCompleteResult result = service.complete(command(past, null, null, "깜빡했던 날"));

            assertThat(result.status()).isEqualTo(ExecutionStatus.COMPLETED);
            assertThat(result.scheduledDate()).isEqualTo(past);
        }

        @Test
        @DisplayName("아직오지않은날짜면_검증예외를던진다")
        void complete_futureDate_throwsValidation() {
            when(routineRepository.findByIdAndUserId(ROUTINE_ID, USER_ID))
                    .thenReturn(Optional.of(routine(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 8, 1))));

            assertThatThrownBy(() -> service.complete(command(TODAY.plusDays(1), null, null, null)))
                    .isInstanceOfSatisfying(BusinessException.class, e ->
                            assertThat(e.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
            verify(executionRepository, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("중단한루틴이면_완료할수없다")
        void complete_whenInactiveRoutine_throwsValidation() {
            when(routineRepository.findByIdAndUserId(ROUTINE_ID, USER_ID))
                    .thenReturn(Optional.of(inactiveRoutine(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 8, 1))));

            assertThatThrownBy(() -> service.complete(command(TODAY, null, null, null)))
                    .isInstanceOfSatisfying(BusinessException.class, e ->
                            assertThat(e.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
            verify(executionRepository, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("루틴수행기간밖이면_검증예외를던진다")
        void complete_whenOutOfPeriod_throwsValidation() {
            when(routineRepository.findByIdAndUserId(ROUTINE_ID, USER_ID))
                    .thenReturn(Optional.of(routine(TODAY.plusDays(1), TODAY.plusDays(10))));

            assertThatThrownBy(() -> service.complete(command(TODAY, null, null, null)))
                    .isInstanceOfSatisfying(BusinessException.class, e ->
                            assertThat(e.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
            verify(executionRepository, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("지정형인데_오늘이수행요일이아니면_검증예외를던진다")
        void complete_whenSpecificDaysOffDay_throwsValidation() {
            short offDayMask = (short) (1 << (TODAY.getDayOfWeek().getValue() % 7)); // 오늘 요일 제외
            when(routineRepository.findByIdAndUserId(ROUTINE_ID, USER_ID))
                    .thenReturn(Optional.of(routine(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 8, 1))));
            when(templateRepository.findById(TEMPLATE_ID))
                    .thenReturn(Optional.of(template(ScheduleType.SPECIFIC_DAYS, offDayMask, null)));

            assertThatThrownBy(() -> service.complete(command(TODAY, null, null, null)))
                    .isInstanceOfSatisfying(BusinessException.class, e ->
                            assertThat(e.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
            verify(executionRepository, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("본인루틴이아니면_ROUTINE_NOT_FOUND")
        void complete_whenNotOwned_throwsNotFound() {
            when(routineRepository.findByIdAndUserId(ROUTINE_ID, USER_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.complete(command(TODAY, null, null, null)))
                    .isInstanceOfSatisfying(BusinessException.class, e ->
                            assertThat(e.getErrorCode()).isEqualTo(ErrorCode.ROUTINE_NOT_FOUND));
        }

        @Test
        @DisplayName("유효한사진이면_저장소에업로드하고_URL을담아완료한다")
        void complete_withValidPhoto_uploadsAndSaves() {
            byte[] jpeg = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x00, 0x10, 0x20};
            when(routineRepository.findByIdAndUserId(ROUTINE_ID, USER_ID))
                    .thenReturn(Optional.of(routine(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 8, 1))));
            when(templateRepository.findById(TEMPLATE_ID))
                    .thenReturn(Optional.of(template(ScheduleType.DAILY, null, null)));
            when(executionRepository.findByRoutineIdAndScheduledDate(ROUTINE_ID, TODAY))
                    .thenReturn(Optional.empty());
            when(fileStorage.upload(any())).thenReturn(new StoredFile("routine-executions/abc.jpg", "https://cdn/abc.jpg"));
            when(executionRepository.saveAndFlush(any(RoutineExecution.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            ExecutionCompleteResult result = service.complete(command(TODAY, jpeg, "image/jpeg", null));

            assertThat(result.status()).isEqualTo(ExecutionStatus.COMPLETED);
            assertThat(result.photoUrl()).isEqualTo("https://cdn/abc.jpg");
            verify(fileStorage).upload(any());
        }

        @Test
        @DisplayName("지원하지않는사진형식이면_업로드하지않고_예외를던진다")
        void complete_whenUnsupportedImage_throws() {
            byte[] notImage = {0x00, 0x01, 0x02, 0x03};
            when(routineRepository.findByIdAndUserId(ROUTINE_ID, USER_ID))
                    .thenReturn(Optional.of(routine(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 8, 1))));
            when(templateRepository.findById(TEMPLATE_ID))
                    .thenReturn(Optional.of(template(ScheduleType.DAILY, null, null)));
            when(executionRepository.findByRoutineIdAndScheduledDate(ROUTINE_ID, TODAY))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.complete(command(TODAY, notImage, "image/jpeg", null)))
                    .isInstanceOfSatisfying(BusinessException.class, e ->
                            assertThat(e.getErrorCode()).isEqualTo(ErrorCode.UNSUPPORTED_IMAGE_TYPE));
            verify(fileStorage, never()).upload(any());
            verify(executionRepository, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("동시완료경합으로_uq_re_routine_date충돌이나면_409_EXECUTION_ALREADY_COMPLETED로변환한다")
        void complete_whenUniqueViolation_translatesToConflict() {
            when(routineRepository.findByIdAndUserId(ROUTINE_ID, USER_ID))
                    .thenReturn(Optional.of(routine(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 8, 1))));
            when(templateRepository.findById(TEMPLATE_ID))
                    .thenReturn(Optional.of(template(ScheduleType.DAILY, null, null)));
            when(executionRepository.findByRoutineIdAndScheduledDate(ROUTINE_ID, TODAY))
                    .thenReturn(Optional.empty());
            when(executionRepository.saveAndFlush(any(RoutineExecution.class)))
                    .thenThrow(new DataIntegrityViolationException("uq_re_routine_date"));

            assertThatThrownBy(() -> service.complete(command(TODAY, null, null, null)))
                    .isInstanceOfSatisfying(BusinessException.class, e ->
                            assertThat(e.getErrorCode()).isEqualTo(ErrorCode.EXECUTION_ALREADY_COMPLETED));
        }
    }

    @Nested
    @DisplayName("cancelComplete")
    class CancelComplete {

        @Test
        @DisplayName("오늘완료분을취소하면_행을삭제하고_PENDING결과를반환한다")
        void cancel_success() {
            RoutineExecution execution = RoutineExecution.completed(
                    ROUTINE_ID, USER_ID, TODAY, LocalDateTime.now(CLOCK), "url", "key", "memo");
            when(routineRepository.findByIdAndUserId(ROUTINE_ID, USER_ID))
                    .thenReturn(Optional.of(routine(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 8, 1))));
            when(executionRepository.findByRoutineIdAndScheduledDate(ROUTINE_ID, TODAY))
                    .thenReturn(Optional.of(execution));

            ExecutionCompleteResult result = service.cancelComplete(ROUTINE_ID, USER_ID, TODAY);

            assertThat(result.status()).isEqualTo(ExecutionStatus.PENDING);
            verify(executionRepository).delete(execution);
        }

        @Test
        @DisplayName("완료기록이없으면_EXECUTION_NOT_FOUND")
        void cancel_whenNotFound_throwsNotFound() {
            when(routineRepository.findByIdAndUserId(ROUTINE_ID, USER_ID))
                    .thenReturn(Optional.of(routine(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 8, 1))));
            when(executionRepository.findByRoutineIdAndScheduledDate(ROUTINE_ID, TODAY))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.cancelComplete(ROUTINE_ID, USER_ID, TODAY))
                    .isInstanceOfSatisfying(BusinessException.class, e ->
                            assertThat(e.getErrorCode()).isEqualTo(ErrorCode.EXECUTION_NOT_FOUND));
            verify(executionRepository, never()).delete(any());
        }

        @Test
        @DisplayName("지난날짜의완료분도_취소할수있다")
        void cancel_pastDate_success() {
            LocalDate past = TODAY.minusDays(3);
            RoutineExecution execution = RoutineExecution.completed(
                    ROUTINE_ID, USER_ID, past, LocalDateTime.now(CLOCK), null, null, null);
            when(routineRepository.findByIdAndUserId(ROUTINE_ID, USER_ID))
                    .thenReturn(Optional.of(routine(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 8, 1))));
            when(executionRepository.findByRoutineIdAndScheduledDate(ROUTINE_ID, past))
                    .thenReturn(Optional.of(execution));

            ExecutionCompleteResult result = service.cancelComplete(ROUTINE_ID, USER_ID, past);

            assertThat(result.status()).isEqualTo(ExecutionStatus.PENDING);
            verify(executionRepository).delete(execution);
        }

        @Test
        @DisplayName("중단한루틴이어도_이미남긴완료는취소할수있다")
        void cancel_whenInactiveRoutine_success() {
            RoutineExecution execution = RoutineExecution.completed(
                    ROUTINE_ID, USER_ID, TODAY, LocalDateTime.now(CLOCK), null, null, null);
            when(routineRepository.findByIdAndUserId(ROUTINE_ID, USER_ID))
                    .thenReturn(Optional.of(inactiveRoutine(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 8, 1))));
            when(executionRepository.findByRoutineIdAndScheduledDate(ROUTINE_ID, TODAY))
                    .thenReturn(Optional.of(execution));

            service.cancelComplete(ROUTINE_ID, USER_ID, TODAY);

            verify(executionRepository).delete(execution);
        }
    }

    @Nested
    @DisplayName("getMyExecutions")
    class GetMyExecutions {

        @Test
        @DisplayName("저장된완료와_파생PENDING을_병합해_날짜내림차순으로반환한다")
        void mergesStoredAndDerived() {
            LocalDate start = TODAY.minusDays(1);
            Routine routine = routine(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 8, 1));
            RoutineExecution completedYesterday = RoutineExecution.completed(
                    ROUTINE_ID, USER_ID, start, LocalDateTime.of(start, LocalTime.NOON), "u", "k", null);
            ReflectionTestUtils.setField(completedYesterday, "id", 500L);

            when(routineRepository.findForExecutionDerivation(USER_ID, null, start, TODAY))
                    .thenReturn(List.of(routine));
            when(templateRepository.findAllById(any()))
                    .thenReturn(List.of(template(ScheduleType.DAILY, null, null)));
            when(executionRepository.findCompletedInRange(USER_ID, null, start, TODAY))
                    .thenReturn(List.of(completedYesterday));

            List<ExecutionResult> results = service.getMyExecutions(USER_ID, null, null, start, TODAY);

            assertThat(results).extracting(ExecutionResult::scheduledDate, ExecutionResult::status)
                    .containsExactly(
                            org.assertj.core.groups.Tuple.tuple(TODAY, ExecutionStatus.PENDING),
                            org.assertj.core.groups.Tuple.tuple(start, ExecutionStatus.COMPLETED));
            assertThat(results.get(1).executionId()).isEqualTo(500L);
            assertThat(results.get(1).title()).isEqualTo("아침 러닝");
        }

        @Test
        @DisplayName("status필터를주면_해당상태만반환한다")
        void statusFilter() {
            LocalDate start = TODAY.minusDays(1);
            Routine routine = routine(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 8, 1));
            RoutineExecution completedYesterday = RoutineExecution.completed(
                    ROUTINE_ID, USER_ID, start, LocalDateTime.of(start, LocalTime.NOON), null, null, null);

            when(routineRepository.findForExecutionDerivation(USER_ID, null, start, TODAY))
                    .thenReturn(List.of(routine));
            when(templateRepository.findAllById(any()))
                    .thenReturn(List.of(template(ScheduleType.DAILY, null, null)));
            when(executionRepository.findCompletedInRange(USER_ID, null, start, TODAY))
                    .thenReturn(List.of(completedYesterday));

            List<ExecutionResult> results =
                    service.getMyExecutions(USER_ID, null, ExecutionStatus.COMPLETED, start, TODAY);

            assertThat(results).extracting(ExecutionResult::status).containsExactly(ExecutionStatus.COMPLETED);
        }

        @Test
        @DisplayName("중단한루틴은_완료이력만보이고_PENDING을파생하지않는다")
        void inactiveRoutine_showsHistoryOnly() {
            LocalDate start = TODAY.minusDays(1);
            Routine stopped = inactiveRoutine(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 8, 1));
            RoutineExecution completedYesterday = RoutineExecution.completed(
                    ROUTINE_ID, USER_ID, start, LocalDateTime.of(start, LocalTime.NOON), null, null, null);

            when(routineRepository.findForExecutionDerivation(USER_ID, null, start, TODAY))
                    .thenReturn(List.of(stopped));
            when(templateRepository.findAllById(any()))
                    .thenReturn(List.of(template(ScheduleType.DAILY, null, null)));
            when(executionRepository.findCompletedInRange(USER_ID, null, start, TODAY))
                    .thenReturn(List.of(completedYesterday));

            List<ExecutionResult> results = service.getMyExecutions(USER_ID, null, null, start, TODAY);

            assertThat(results).extracting(ExecutionResult::scheduledDate, ExecutionResult::status)
                    .containsExactly(
                            org.assertj.core.groups.Tuple.tuple(start, ExecutionStatus.COMPLETED));
        }

        @Test
        @DisplayName("대상루틴이없으면_빈목록을반환한다")
        void whenNoRoutines_returnsEmpty() {
            when(routineRepository.findForExecutionDerivation(USER_ID, null, TODAY, TODAY))
                    .thenReturn(List.of());

            assertThat(service.getMyExecutions(USER_ID, null, null, TODAY, TODAY)).isEmpty();
        }
    }
}
