package com.routinely.routine_service.application.execution;

import com.routinely.routine_service.domain.definition.RoutineDefinition;
import com.routinely.routine_service.domain.execution.RoutineExecutionRepository;
import com.routinely.routine_service.domain.routine.Routine;
import com.routinely.routine_service.domain.schedule.WeekBoundary;
import com.routinely.routine_service.domain.template.ScheduleType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("기간별 인정 횟수 캡")
class AcceptedCountCalculatorTest {
    private final RoutineExecutionRepository repository = mock(RoutineExecutionRepository.class);
    private final AcceptedCountCalculator calculator = new AcceptedCountCalculator(repository);

    private int count(ScheduleType type, Integer target, List<LocalDate> dates) {
        var routine = Routine.forPersonal(null, 1L,
                RoutineDefinition.of("루틴", "HEALTH", type, type == ScheduleType.SPECIFIC_DAYS ? (short) 127 : null, target),
                LocalDate.of(2026, 1, 1), null, null);
        when(repository.findCompletedDates(null, routine.getStartedAt(), null)).thenReturn(dates);
        return calculator.calculate(routine);
    }

    @Test @DisplayName("주 3회 목표에서 여섯 번 완료해도 세 번만 인정한다")
    void calculate_weeklyCapsAtThree() {
        var dates = LocalDate.of(2026, 9, 6).datesUntil(LocalDate.of(2026, 9, 12)).toList();
        assertThat(count(ScheduleType.WEEKLY_COUNT, 3, dates)).isEqualTo(3);
        assertThat(count(ScheduleType.WEEKLY_COUNT, 3, dates.subList(0, 3))).isEqualTo(3);
        assertThat(count(ScheduleType.DAILY, null, dates)).isEqualTo(6);
        assertThat(count(ScheduleType.SPECIFIC_DAYS, null, dates)).isEqualTo(6);
    }

    @Test @DisplayName("토요일 23시 30분은 이전 일요일의 주에 속하고 다음 일요일은 새 주다")
    void weekBoundary_usesKoreanSunday() {
        assertThat(WeekBoundary.startOfWeek(Instant.parse("2026-09-12T14:30:00Z")))
                .isEqualTo(LocalDate.of(2026, 9, 6));
        assertThat(WeekBoundary.startOfWeek(Instant.parse("2026-09-12T15:00:00Z")))
                .isEqualTo(LocalDate.of(2026, 9, 13));
    }

    @Test @DisplayName("불완전한 시작 주와 종료 주도 각각 원래 목표를 적용한다")
    void calculate_partialWeeksKeepTarget() {
        var dates = List.of(LocalDate.of(2026, 9, 9), LocalDate.of(2026, 9, 10),
                LocalDate.of(2026, 9, 11), LocalDate.of(2026, 9, 12), LocalDate.of(2026, 9, 13));
        assertThat(count(ScheduleType.WEEKLY_COUNT, 3, dates)).isEqualTo(4);
    }

    @Test @DisplayName("달력 월마다 캡을 적용하며 연도 경계에서 서로 다른 달로 센다")
    void calculate_monthlyCapsAcrossYears() {
        var dates = List.of(LocalDate.of(2025, 12, 30), LocalDate.of(2025, 12, 31),
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 2));
        assertThat(count(ScheduleType.MONTHLY_COUNT, 1, dates)).isEqualTo(2);
        assertThat(count(ScheduleType.MONTHLY_COUNT, 1, List.of())).isZero();
    }
}
