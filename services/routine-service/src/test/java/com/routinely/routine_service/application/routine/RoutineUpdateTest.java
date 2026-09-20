package com.routinely.routine_service.application.routine;

import com.routinely.core.exception.BusinessException;
import com.routinely.core.exception.ErrorCode;
import com.routinely.routine_service.application.routine.dto.UpdateRoutineCommand;
import com.routinely.routine_service.application.routine.dto.StartRoutineCommand;
import com.routinely.routine_service.domain.category.CategoryRepository;
import com.routinely.routine_service.domain.definition.RoutineDefinition;
import com.routinely.routine_service.domain.execution.RoutineExecution;
import com.routinely.routine_service.domain.execution.RoutineExecutionRepository;
import com.routinely.routine_service.domain.routine.Routine;
import com.routinely.routine_service.domain.routine.RoutineRepository;
import com.routinely.routine_service.domain.template.RoutineTemplateRepository;
import com.routinely.routine_service.domain.template.ScheduleType;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.*;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("루틴 독립 정의와 수정 정책")
class RoutineUpdateTest {
    @Mock RoutineRepository routines;
    @Mock RoutineTemplateRepository templates;
    @Mock RoutineExecutionRepository executions;
    @Mock CategoryRepository categories;
    RoutineService service;
    private final LocalDate start = LocalDate.of(2026, 9, 1);
    private final RoutineDefinition definition = RoutineDefinition.of("원래 제목", "HEALTH", ScheduleType.DAILY, null, null);
    private Routine routine;
    @BeforeEach void setUp() {
        service = new RoutineServiceImpl(routines, templates, executions, categories);
        routine = Routine.forPersonal(null, 1L, definition, start, start.plusDays(30), LocalTime.NOON);
        ReflectionTestUtils.setField(routine, "id", 1L);
        routine.changePreferences(LocalTime.NOON, (short)21);
    }
    private UpdateRoutineCommand title(String title) {
        return new UpdateRoutineCommand(title, null, null, null, null, null, null, null, null, false, false, false);
    }
    private UpdateRoutineCommand end(LocalDate end, boolean clear) {
        return new UpdateRoutineCommand(null, null, null, null, null, null, end, null, null, clear, false, false);
    }
    private void owned() { when(routines.findLockedByIdAndUserId(1L, 1L)).thenReturn(Optional.of(routine)); }

    @Test @DisplayName("제목만 수정하면 선호 설정과 스케줄은 그대로 유지한다")
    void update_titlePreservesPreferences() {
        owned();
        service.update(1L, 1L, title("새 제목"));
        assertThat(routine.getDefinition().getTitle()).isEqualTo("새 제목");
        assertThat(routine.getPreferredTime()).isEqualTo(LocalTime.NOON);
        assertThat(routine.getPreferredDays()).isEqualTo((short)21);
        assertThat(routine.getDefinition().getScheduleType()).isEqualTo(ScheduleType.DAILY);
    }
    @Test @DisplayName("명시적 해제 옵션은 종료일과 선호 설정을 비운다")
    void update_clearFields() {
        owned();
        service.update(1L, 1L, new UpdateRoutineCommand(null, null, null, null, null, null, null, null, null, true, true, true));
        assertThat(routine.getEndedAt()).isNull();
        assertThat(routine.getPreferredTime()).isNull();
        assertThat(routine.getPreferredDays()).isNull();
    }
    @Test @DisplayName("마지막 완료일보다 앞으로 종료일을 단축할 수 없다")
    void update_cannotHideCompletedDate() {
        owned();
        when(executions.findTopByRoutineIdOrderByScheduledDateDesc(1L)).thenReturn(Optional.of(
                RoutineExecution.completed(1L, 1L, start.plusDays(5), LocalDateTime.now())));
        assertThatThrownBy(() -> service.update(1L, 1L, end(start.plusDays(4), false)))
                .isInstanceOf(BusinessException.class);
        service.update(1L, 1L, end(start.plusDays(5), false));
        assertThat(routine.getEndedAt()).isEqualTo(start.plusDays(5));
    }
    @Test @DisplayName("무기한 전환은 완료 기록이 있어도 허용한다")
    void update_noEndDateAllowed() {
        owned();
        service.update(1L, 1L, end(null, true));
        assertThat(routine.getEndedAt()).isNull();
        verifyNoInteractions(executions);
    }
    @Test @DisplayName("종료일이 시작일보다 빠르거나 366일 기간을 넘으면 거부한다")
    void update_periodBounds() {
        owned();
        assertThatThrownBy(() -> service.update(1L, 1L, end(start.minusDays(1), false))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.update(1L, 1L, end(start.plusDays(366), false))).isInstanceOf(BusinessException.class);
        service.update(1L, 1L, end(start.plusDays(365), false));
        assertThat(routine.getEndedAt()).isEqualTo(start.plusDays(365));
    }
    @ParameterizedTest @ValueSource(strings = {"title", "category", "schedule", "start", "end", "clearEnd"})
    @DisplayName("챌린지 루틴의 모든 정의와 기간 수정은 403이다")
    void update_challengeFieldsForbidden(String field) {
        owned();
        ReflectionTestUtils.setField(routine, "challengeId", 9L);
        var command = new UpdateRoutineCommand(field.equals("title") ? "제목" : null,
                field.equals("category") ? "HEALTH" : null, field.equals("schedule") ? "DAILY" : null,
                null, null, field.equals("start") ? start : null, field.equals("end") ? start.plusDays(50) : null,
                null, null, field.equals("clearEnd"), false, false);
        assertThatThrownBy(() -> service.update(1L, 1L, command)).isInstanceOfSatisfying(BusinessException.class,
                ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
    }
    @Test @DisplayName("챌린지 루틴의 개인 선호 설정은 수정할 수 있다")
    void update_challengePreferencesAllowed() {
        owned();
        ReflectionTestUtils.setField(routine, "challengeId", 9L);
        service.update(1L, 1L, new UpdateRoutineCommand(null, null, null, null, null, null, null,
                LocalTime.of(7,0), (short)3, false, false, false));
        assertThat(routine.getPreferredTime()).isEqualTo(LocalTime.of(7,0));
        assertThat(routine.getPreferredDays()).isEqualTo((short)3);
    }
    @Test @DisplayName("직접 생성은 템플릿을 조회하지 않으며 무기한 루틴을 저장한다")
    void start_directDefinitionDoesNotQueryTemplate() {
        when(categories.existsByCodeAndIsActiveTrue("HEALTH")).thenReturn(true);
        when(routines.save(any())).thenAnswer(i -> i.getArgument(0));
        var result = service.start(new StartRoutineCommand(1L, null, start, null, null, definition));
        assertThat(result.routineTemplateId()).isNull();
        assertThat(result.endedAt()).isNull();
        assertThat(result.title()).isEqualTo("원래 제목");
        verifyNoInteractions(templates);
    }

    @Test @DisplayName("직접 생성은 없거나 비활성인 카테고리 코드를 거부하고 저장하지 않는다")
    void start_directDefinitionRejectsUnknownCategory() {
        var unknown = RoutineDefinition.of("원래 제목", "ABC", ScheduleType.DAILY, null, null);
        when(categories.existsByCodeAndIsActiveTrue("ABC")).thenReturn(false);
        assertThatThrownBy(() -> service.start(new StartRoutineCommand(1L, null, start, null, null, unknown)))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        verify(routines, never()).save(any());
    }

    @Test @DisplayName("카테고리 수정은 없거나 비활성인 코드를 거부하고 기존 정의를 유지한다")
    void update_rejectsUnknownCategory() {
        owned();
        when(categories.existsByCodeAndIsActiveTrue("ABC")).thenReturn(false);
        var command = new UpdateRoutineCommand(null, "ABC", null, null, null, null, null, null, null, false, false, false);
        assertThatThrownBy(() -> service.update(1L, 1L, command))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        assertThat(routine.getDefinition().getCategoryCode()).isEqualTo("HEALTH");
    }

    @Test @DisplayName("카테고리 수정은 활성 코드면 반영한다")
    void update_acceptsActiveCategory() {
        owned();
        when(categories.existsByCodeAndIsActiveTrue("EXERCISE")).thenReturn(true);
        service.update(1L, 1L, new UpdateRoutineCommand(null, "EXERCISE", null, null, null, null, null, null, null, false, false, false));
        assertThat(routine.getDefinition().getCategoryCode()).isEqualTo("EXERCISE");
    }

    @Test @DisplayName("완료 이력이 0건이면 주기와 시작일을 수정할 수 있다")
    void update_historyGuard_noHistoryAllowsSchedule() {
        owned();
        when(executions.existsByRoutineId(1L)).thenReturn(false);
        service.update(1L, 1L, new UpdateRoutineCommand(null, null, "WEEKLY_COUNT", null, 3,
                start.plusDays(1), null, null, null, false, false, false));
        assertThat(routine.getDefinition().getScheduleType()).isEqualTo(ScheduleType.WEEKLY_COUNT);
        assertThat(routine.getDefinition().getTargetCount()).isEqualTo(3);
        assertThat(routine.getStartedAt()).isEqualTo(start.plusDays(1));
    }

    @Test @DisplayName("완료 이력이 1건이라도 있으면 주기 수정을 거부하고 기존 정의를 유지한다")
    void update_historyGuard_scheduleRejectedWhenHistoryExists() {
        owned();
        when(executions.existsByRoutineId(1L)).thenReturn(true);
        var command = new UpdateRoutineCommand(null, null, "WEEKLY_COUNT", null, 3,
                null, null, null, null, false, false, false);
        assertThatThrownBy(() -> service.update(1L, 1L, command))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        assertThat(routine.getDefinition().getScheduleType()).isEqualTo(ScheduleType.DAILY);
        assertThat(routine.getDefinition().getTargetCount()).isNull();
    }

    @Test @DisplayName("완료 이력이 있으면 시작일 수정도 거부하고 기존 기간을 유지한다")
    void update_historyGuard_startDateRejectedWhenHistoryExists() {
        owned();
        when(executions.existsByRoutineId(1L)).thenReturn(true);
        var command = new UpdateRoutineCommand(null, null, null, null, null,
                start.plusDays(3), null, null, null, false, false, false);
        assertThatThrownBy(() -> service.update(1L, 1L, command))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        assertThat(routine.getStartedAt()).isEqualTo(start);
        assertThat(routine.getEndedAt()).isEqualTo(start.plusDays(30));
    }

    @Test @DisplayName("제목만 수정하면 완료 이력을 조회하지 않는다")
    void update_historyGuard_titleSkipsHistoryCheck() {
        owned();
        service.update(1L, 1L, title("새 제목"));
        verify(executions, never()).existsByRoutineId(any());
    }
}
