package com.routinely.routine_service.domain.execution;

import com.routinely.jpa.config.JpaAuditingConfig;
import org.springframework.context.annotation.Import;

import com.routinely.routine_service.domain.routine.Routine;
import com.routinely.routine_service.domain.routine.RoutineRepository;
import com.routinely.routine_service.domain.template.RoutineTemplate;
import com.routinely.routine_service.domain.template.RoutineTemplateRepository;
import com.routinely.routine_service.domain.template.ScheduleType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@Import(JpaAuditingConfig.class)
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@DisplayName("RoutineExecutionRepository")
class RoutineExecutionRepositoryTest {

    private static final LocalDate D1 = LocalDate.of(2026, 7, 20);
    private static final LocalDate D2 = LocalDate.of(2026, 7, 22);
    private static final LocalDate D3 = LocalDate.of(2026, 7, 24);

    @Autowired
    private RoutineExecutionRepository executionRepository;

    @Autowired
    private RoutineRepository routineRepository;

    @Autowired
    private RoutineTemplateRepository templateRepository;

    private Long saveRoutine(Long userId) {
        RoutineTemplate template = templateRepository.saveAndFlush(
                RoutineTemplate.forPersonal(userId, "아침 러닝 30분", "EXERCISE", ScheduleType.DAILY, null, null));
        Routine routine = routineRepository.saveAndFlush(Routine.forPersonal(
                template.getId(), userId, LocalDate.of(2026, 7, 1), LocalDate.of(2026, 8, 1), null));
        return routine.getId();
    }

    private RoutineExecution saveCompleted(Long routineId, Long userId, LocalDate date) {
        return executionRepository.saveAndFlush(RoutineExecution.completed(
                routineId, userId, date, LocalDateTime.of(date, java.time.LocalTime.NOON), null, null, null));
    }

    @Test
    @DisplayName("루틴과날짜로_저장된완료기록을조회한다")
    void findByRoutineIdAndScheduledDate_returnsRow() {
        Long routineId = saveRoutine(1L);
        saveCompleted(routineId, 1L, D2);

        Optional<RoutineExecution> found = executionRepository.findByRoutineIdAndScheduledDate(routineId, D2);

        assertThat(found).isPresent();
        assertThat(found.get().getStatus()).isEqualTo(ExecutionStatus.COMPLETED);
        assertThat(found.get().getScheduledDate()).isEqualTo(D2);
    }

    @Test
    @DisplayName("완료기록이없는_루틴날짜는_빈값이다")
    void findByRoutineIdAndScheduledDate_whenNone_returnsEmpty() {
        Long routineId = saveRoutine(1L);

        assertThat(executionRepository.findByRoutineIdAndScheduledDate(routineId, D2)).isEmpty();
    }

    @Test
    @DisplayName("기간조회는_본인의완료기록만_기간내로반환한다")
    void findCompletedInRange_filtersUserAndRange() {
        Long myRoutine = saveRoutine(1L);
        Long otherRoutine = saveRoutine(2L);
        saveCompleted(myRoutine, 1L, D1);
        saveCompleted(myRoutine, 1L, D3);
        saveCompleted(otherRoutine, 2L, D2); // 다른 사용자

        List<RoutineExecution> results =
                executionRepository.findCompletedInRange(1L, null, D1, D2);

        assertThat(results).extracting(RoutineExecution::getScheduledDate).containsExactlyInAnyOrder(D1);
    }

    @Test
    @DisplayName("routineId필터는_해당루틴의완료기록만반환한다")
    void findCompletedInRange_withRoutineFilter() {
        Long routineA = saveRoutine(1L);
        Long routineB = saveRoutine(1L);
        saveCompleted(routineA, 1L, D2);
        saveCompleted(routineB, 1L, D2);

        List<RoutineExecution> results =
                executionRepository.findCompletedInRange(1L, routineA, D1, D3);

        assertThat(results).extracting(RoutineExecution::getRoutineId).containsExactly(routineA);
    }

    @Test
    @DisplayName("완료(COMPLETED)가아닌_행은_기간조회에서제외된다")
    void findCompletedInRange_excludesNonCompleted() {
        Long routineId = saveRoutine(1L);
        RoutineExecution pending = saveCompleted(routineId, 1L, D2);
        // sparse 저장상 정상적으로는 없지만, 쿼리의 status 필터를 방어적으로 검증한다.
        ReflectionTestUtils.setField(pending, "status", ExecutionStatus.PENDING);
        ReflectionTestUtils.setField(pending, "completedAt", null);
        executionRepository.saveAndFlush(pending);

        List<RoutineExecution> results =
                executionRepository.findCompletedInRange(1L, null, D1, D3);

        assertThat(results).isEmpty();
    }
}
