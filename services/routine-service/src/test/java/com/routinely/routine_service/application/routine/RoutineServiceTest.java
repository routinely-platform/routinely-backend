package com.routinely.routine_service.application.routine;

import com.routinely.core.exception.BusinessException;
import com.routinely.core.exception.ErrorCode;
import com.routinely.routine_service.application.routine.dto.PreferencesResult;
import com.routinely.routine_service.application.routine.dto.RoutineResult;
import com.routinely.routine_service.application.routine.dto.StartRoutineCommand;
import com.routinely.routine_service.domain.routine.Routine;
import com.routinely.routine_service.domain.routine.RoutineRepository;
import com.routinely.routine_service.domain.template.RoutineTemplate;
import com.routinely.routine_service.domain.template.RoutineTemplateRepository;
import com.routinely.routine_service.domain.template.ScheduleType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("RoutineService")
class RoutineServiceTest {

    private static final Long OWNER_ID = 1L;
    private static final Long OTHER_USER_ID = 2L;
    private static final Long TEMPLATE_ID = 10L;
    private static final Long ROUTINE_ID = 100L;
    private static final LocalDate START = LocalDate.of(2026, 2, 1);
    private static final LocalDate END = LocalDate.of(2026, 3, 2);

    private RoutineRepository routineRepository;
    private RoutineTemplateRepository templateRepository;
    private RoutineService service;

    @BeforeEach
    void setUp() {
        routineRepository = mock(RoutineRepository.class);
        templateRepository = mock(RoutineTemplateRepository.class);
        service = new RoutineService(routineRepository, templateRepository);
    }

    private RoutineTemplate personalTemplate() {
        RoutineTemplate template = RoutineTemplate.forPersonal(
                OWNER_ID, "아침 러닝 30분", "EXERCISE", ScheduleType.DAILY, null, null);
        ReflectionTestUtils.setField(template, "id", TEMPLATE_ID);
        return template;
    }

    private RoutineTemplate challengeTemplate() {
        RoutineTemplate template = RoutineTemplate.forChallenge(
                OWNER_ID, 42L, "아침 러닝 30분", "EXERCISE", ScheduleType.WEEKLY_COUNT, null, 3);
        ReflectionTestUtils.setField(template, "id", TEMPLATE_ID);
        return template;
    }

    private Routine personalRoutine(Long templateId, LocalTime preferredTime) {
        Routine routine = Routine.forPersonal(templateId, OWNER_ID, START, END, preferredTime);
        ReflectionTestUtils.setField(routine, "id", ROUTINE_ID);
        return routine;
    }

    private Routine challengeRoutine(Long challengeId) {
        Routine routine = personalRoutine(TEMPLATE_ID, null);
        // 챌린지 루틴 인스턴스는 challenge.started 소비로 생성되며(ADR-0032, 별도 구현) 아직 팩토리가 없다.
        ReflectionTestUtils.setField(routine, "challengeId", challengeId);
        return routine;
    }

    @Nested
    @DisplayName("start")
    class Start {

        @Test
        @DisplayName("유효한커맨드면_개인루틴을저장하고템플릿제목을담아반환한다")
        void start_whenValid_savesPersonalRoutine() {
            when(templateRepository.findByIdAndIsDeletedFalse(TEMPLATE_ID))
                    .thenReturn(Optional.of(personalTemplate()));
            when(routineRepository.save(any(Routine.class)))
                    .thenAnswer(invocation -> {
                        Routine argument = invocation.getArgument(0);
                        ReflectionTestUtils.setField(argument, "id", ROUTINE_ID);
                        return argument;
                    });

            RoutineResult result = service.start(new StartRoutineCommand(
                    OWNER_ID, TEMPLATE_ID, START, END, LocalTime.of(7, 0)));

            ArgumentCaptor<Routine> captor = ArgumentCaptor.forClass(Routine.class);
            verify(routineRepository).save(captor.capture());
            Routine saved = captor.getValue();
            assertThat(saved.getRoutineTemplateId()).isEqualTo(TEMPLATE_ID);
            assertThat(saved.getUserId()).isEqualTo(OWNER_ID);
            assertThat(saved.getChallengeId()).isNull();
            assertThat(saved.isActive()).isTrue();

            assertThat(result.routineId()).isEqualTo(ROUTINE_ID);
            assertThat(result.title()).isEqualTo("아침 러닝 30분");
            assertThat(result.preferredTime()).isEqualTo(LocalTime.of(7, 0));
            assertThat(result.isActive()).isTrue();
        }

        @Test
        @DisplayName("선호시각이null이어도_루틴을저장한다")
        void start_whenPreferredTimeNull_savesRoutine() {
            when(templateRepository.findByIdAndIsDeletedFalse(TEMPLATE_ID))
                    .thenReturn(Optional.of(personalTemplate()));
            when(routineRepository.save(any(Routine.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            RoutineResult result = service.start(new StartRoutineCommand(
                    OWNER_ID, TEMPLATE_ID, START, END, null));

            assertThat(result.preferredTime()).isNull();
        }

        @Test
        @DisplayName("템플릿이없거나삭제됐으면_ROUTINE_TEMPLATE_NOT_FOUND예외를던진다")
        void start_whenTemplateNotFound_throwsTemplateNotFound() {
            when(templateRepository.findByIdAndIsDeletedFalse(TEMPLATE_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.start(new StartRoutineCommand(
                    OWNER_ID, TEMPLATE_ID, START, END, null)))
                    .isInstanceOfSatisfying(BusinessException.class, exception ->
                            assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.ROUTINE_TEMPLATE_NOT_FOUND));

            verify(routineRepository, never()).save(any());
        }

        @Test
        @DisplayName("본인템플릿이아니면_FORBIDDEN예외를던진다")
        void start_whenNotOwner_throwsForbidden() {
            when(templateRepository.findByIdAndIsDeletedFalse(TEMPLATE_ID))
                    .thenReturn(Optional.of(personalTemplate()));

            assertThatThrownBy(() -> service.start(new StartRoutineCommand(
                    OTHER_USER_ID, TEMPLATE_ID, START, END, null)))
                    .isInstanceOfSatisfying(BusinessException.class, exception -> {
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN);
                        assertThat(exception.getMessage()).isEqualTo("본인의 루틴 템플릿으로만 루틴을 시작할 수 있습니다.");
                    });

            verify(routineRepository, never()).save(any());
        }

        @Test
        @DisplayName("챌린지연결템플릿이면_FORBIDDEN예외를던진다")
        void start_whenChallengeTemplate_throwsForbidden() {
            when(templateRepository.findByIdAndIsDeletedFalse(TEMPLATE_ID))
                    .thenReturn(Optional.of(challengeTemplate()));

            assertThatThrownBy(() -> service.start(new StartRoutineCommand(
                    OWNER_ID, TEMPLATE_ID, START, END, null)))
                    .isInstanceOfSatisfying(BusinessException.class, exception -> {
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN);
                        assertThat(exception.getMessage()).isEqualTo("챌린지 루틴은 챌린지 시작 시 자동으로 생성됩니다.");
                    });

            verify(routineRepository, never()).save(any());
        }

        @Test
        @DisplayName("종료일이시작일보다빠르면_검증예외를던진다")
        void start_whenEndBeforeStart_throwsValidationFailed() {
            when(templateRepository.findByIdAndIsDeletedFalse(TEMPLATE_ID))
                    .thenReturn(Optional.of(personalTemplate()));

            assertThatThrownBy(() -> service.start(new StartRoutineCommand(
                    OWNER_ID, TEMPLATE_ID, END, START, null)))
                    .isInstanceOfSatisfying(BusinessException.class, exception -> {
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                        assertThat(exception.getMessage()).isEqualTo("종료일은 시작일보다 빠를 수 없습니다.");
                    });

            verify(routineRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("getMyRoutines")
    class GetMyRoutines {

        @Test
        @DisplayName("필터를리포지토리에그대로전달하고_템플릿제목을매핑해반환한다")
        void getMyRoutines_mapsTitlesAndPassesFilters() {
            Routine routine = personalRoutine(TEMPLATE_ID, LocalTime.of(7, 0));
            when(routineRepository.findMyRoutines(OWNER_ID, Boolean.TRUE, null))
                    .thenReturn(List.of(routine));
            when(templateRepository.findAllById(List.of(TEMPLATE_ID)))
                    .thenReturn(List.of(personalTemplate()));

            List<RoutineResult> results = service.getMyRoutines(OWNER_ID, Boolean.TRUE, null);

            assertThat(results).hasSize(1);
            assertThat(results.getFirst().routineId()).isEqualTo(ROUTINE_ID);
            assertThat(results.getFirst().title()).isEqualTo("아침 러닝 30분");
            verify(routineRepository).findMyRoutines(OWNER_ID, Boolean.TRUE, null);
        }

        @Test
        @DisplayName("루틴이없으면_템플릿조회없이빈목록을반환한다")
        void getMyRoutines_whenEmpty_returnsEmpty() {
            when(routineRepository.findMyRoutines(OWNER_ID, null, null)).thenReturn(List.of());
            when(templateRepository.findAllById(List.of())).thenReturn(List.of());

            List<RoutineResult> results = service.getMyRoutines(OWNER_ID, null, null);

            assertThat(results).isEmpty();
        }
    }

    @Nested
    @DisplayName("stop")
    class Stop {

        @Test
        @DisplayName("본인루틴이면_비활성화한다")
        void stop_whenOwned_deactivates() {
            Routine routine = personalRoutine(TEMPLATE_ID, null);
            when(routineRepository.findByIdAndUserId(ROUTINE_ID, OWNER_ID))
                    .thenReturn(Optional.of(routine));

            service.stop(ROUTINE_ID, OWNER_ID);

            assertThat(routine.isActive()).isFalse();
        }

        @Test
        @DisplayName("챌린지루틴이면_중단할수없다")
        void stop_whenChallengeRoutine_throwsForbidden() {
            Routine routine = challengeRoutine(42L);
            when(routineRepository.findByIdAndUserId(ROUTINE_ID, OWNER_ID))
                    .thenReturn(Optional.of(routine));

            assertThatThrownBy(() -> service.stop(ROUTINE_ID, OWNER_ID))
                    .isInstanceOfSatisfying(BusinessException.class, exception ->
                            assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
            assertThat(routine.isActive()).isTrue();
        }

        @Test
        @DisplayName("본인루틴이없으면_ROUTINE_NOT_FOUND예외를던진다")
        void stop_whenNotFound_throwsNotFound() {
            when(routineRepository.findByIdAndUserId(ROUTINE_ID, OTHER_USER_ID))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.stop(ROUTINE_ID, OTHER_USER_ID))
                    .isInstanceOfSatisfying(BusinessException.class, exception ->
                            assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.ROUTINE_NOT_FOUND));
        }
    }

    @Nested
    @DisplayName("updatePreferences")
    class UpdatePreferences {

        private static final short MON_WED_FRI = 0b0010101;

        @Test
        @DisplayName("본인루틴이면_선호시각과선호요일을설정하고결과를반환한다")
        void updatePreferences_whenOwned_setsTimeAndDays() {
            Routine routine = personalRoutine(TEMPLATE_ID, null);
            when(routineRepository.findByIdAndUserId(ROUTINE_ID, OWNER_ID))
                    .thenReturn(Optional.of(routine));

            PreferencesResult result = service.updatePreferences(
                    ROUTINE_ID, OWNER_ID, LocalTime.of(7, 0), MON_WED_FRI);

            assertThat(routine.getPreferredTime()).isEqualTo(LocalTime.of(7, 0));
            assertThat(routine.getPreferredDays()).isEqualTo(MON_WED_FRI);
            assertThat(result.routineId()).isEqualTo(ROUTINE_ID);
            assertThat(result.preferredTime()).isEqualTo(LocalTime.of(7, 0));
            assertThat(result.preferredDays()).isEqualTo(MON_WED_FRI);
        }

        @Test
        @DisplayName("null을전달하면_선호시각과요일을해제한다")
        void updatePreferences_whenNull_clears() {
            Routine routine = personalRoutine(TEMPLATE_ID, LocalTime.of(7, 0));
            when(routineRepository.findByIdAndUserId(ROUTINE_ID, OWNER_ID))
                    .thenReturn(Optional.of(routine));

            PreferencesResult result = service.updatePreferences(ROUTINE_ID, OWNER_ID, null, null);

            assertThat(routine.getPreferredTime()).isNull();
            assertThat(routine.getPreferredDays()).isNull();
            assertThat(result.preferredTime()).isNull();
            assertThat(result.preferredDays()).isNull();
        }

        @Test
        @DisplayName("본인루틴이없거나소유자가아니면_ROUTINE_NOT_FOUND예외를던진다")
        void updatePreferences_whenNotFound_throwsNotFound() {
            when(routineRepository.findByIdAndUserId(ROUTINE_ID, OTHER_USER_ID))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.updatePreferences(
                    ROUTINE_ID, OTHER_USER_ID, LocalTime.of(7, 0), MON_WED_FRI))
                    .isInstanceOfSatisfying(BusinessException.class, exception ->
                            assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.ROUTINE_NOT_FOUND));
        }

        @Test
        @DisplayName("선호요일비트마스크가_범위(1~127)를벗어나면_조회전에_검증예외를던진다")
        void updatePreferences_whenPreferredDaysOutOfRange_throwsValidationFailed() {
            assertThatThrownBy(() -> service.updatePreferences(
                    ROUTINE_ID, OWNER_ID, null, (short) 0))
                    .isInstanceOfSatisfying(BusinessException.class, exception ->
                            assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
            assertThatThrownBy(() -> service.updatePreferences(
                    ROUTINE_ID, OWNER_ID, null, (short) 128))
                    .isInstanceOfSatisfying(BusinessException.class, exception ->
                            assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));

            verify(routineRepository, never()).findByIdAndUserId(any(), any());
        }
    }
}
