package com.routinely.routine_service.application.execution;

import com.routinely.routine_service.domain.definition.RoutineDefinition;
import com.routinely.routine_service.domain.execution.CompletionMark;
import com.routinely.routine_service.domain.execution.RoutineExecutionRepository;
import com.routinely.routine_service.domain.routine.Routine;
import com.routinely.routine_service.domain.schedule.WeekBoundary;
import com.routinely.routine_service.domain.template.ScheduleType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("기간별 인정 횟수 캡과 도달 시각")
class AcceptedCountCalculatorTest {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private final RoutineExecutionRepository repository = mock(RoutineExecutionRepository.class);
    private final AcceptedCountCalculator calculator =
            new AcceptedCountCalculator(repository, Clock.fixed(Instant.parse("2026-09-30T00:00:00Z"), SEOUL));

    /** 수행 날짜 당일 09:00(KST)에 누른 완료 기록. */
    private static CompletionMark onTime(LocalDate date) {
        return new CompletionMark(date, date.atTime(9, 0));
    }

    /** 실제 저장소처럼 completed_at 오름차순으로 정렬해 돌려준다. */
    private AcceptedCount calculate(ScheduleType type, Integer target, List<CompletionMark> marks) {
        var routine = Routine.forPersonal(null, 1L,
                RoutineDefinition.of("루틴", "HEALTH", type, type == ScheduleType.SPECIFIC_DAYS ? (short) 127 : null, target),
                LocalDate.of(2026, 1, 1), null, null);
        var sorted = new ArrayList<>(marks);
        sorted.sort(Comparator.comparing(CompletionMark::completedAt));
        when(repository.findCompletionMarks(null, routine.getStartedAt(), null)).thenReturn(sorted);
        return calculator.calculate(routine);
    }

    private int count(ScheduleType type, Integer target, List<LocalDate> dates) {
        return calculate(type, target, dates.stream().map(AcceptedCountCalculatorTest::onTime).toList()).count();
    }

    private static Instant kst(int month, int day, int hour) {
        return LocalDateTime.of(2026, month, day, hour, 0).atZone(SEOUL).toInstant();
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
    }

    @Test @DisplayName("완료 기록이 없으면 0회이고 도달 시각은 null이다")
    void calculate_noCompletion_zeroWithoutReachedAt() {
        assertThat(calculate(ScheduleType.WEEKLY_COUNT, 3, List.of())).isEqualTo(AcceptedCount.ZERO);
    }

    @Test @DisplayName("캡 초과 완료는 도달 시각을 옮기지 않는다 — 3회째(수)가 도달 시각이다")
    void calculate_overCap_keepsReachedAtOfLastAccepted() {
        var marks = List.of(onTime(LocalDate.of(2026, 9, 21)), onTime(LocalDate.of(2026, 9, 22)),
                onTime(LocalDate.of(2026, 9, 23)), onTime(LocalDate.of(2026, 9, 25)));

        var result = calculate(ScheduleType.WEEKLY_COUNT, 3, marks);

        assertThat(result).isEqualTo(new AcceptedCount(3, kst(9, 23, 9)));
    }

    @Test @DisplayName("캡 안의 기록을 취소하면 초과였던 기록이 인정 대상이 되어 도달 시각이 그 기록으로 바뀐다")
    void calculate_cancelInsideCap_promotesOverCapRecord() {
        // 월·화·수·금 완료 중 화요일 취소 → 남은 월·수·금으로 3회, 금요일에 채웠다
        var marks = List.of(onTime(LocalDate.of(2026, 9, 21)),
                onTime(LocalDate.of(2026, 9, 23)), onTime(LocalDate.of(2026, 9, 25)));

        assertThat(calculate(ScheduleType.WEEKLY_COUNT, 3, marks)).isEqualTo(new AcceptedCount(3, kst(9, 25, 9)));
    }

    @Test @DisplayName("과거 날짜 백필은 수행 날짜의 주로 묶고, 인정 순서와 도달 시각은 누른 시각을 따른다")
    void calculate_backfill_bucketsByScheduledDateOrdersByCompletedAt() {
        var marks = List.of(
                onTime(LocalDate.of(2026, 9, 21)),
                onTime(LocalDate.of(2026, 9, 23)),
                onTime(LocalDate.of(2026, 9, 25)),
                // 화요일 몫을 토요일 10:00에 백필 — 같은 주 4번째로 눌렀으므로 인정되지 않는다
                new CompletionMark(LocalDate.of(2026, 9, 22), LocalDateTime.of(2026, 9, 26, 10, 0)),
                // 지난주 몫을 토요일 11:00에 백필 — 지난주는 아직 비어 있어 인정되고 도달 시각이 된다
                new CompletionMark(LocalDate.of(2026, 9, 16), LocalDateTime.of(2026, 9, 26, 11, 0)));

        assertThat(calculate(ScheduleType.WEEKLY_COUNT, 3, marks)).isEqualTo(new AcceptedCount(4, kst(9, 26, 11)));
    }

    @Test @DisplayName("DAILY는 모든 완료를 인정하고 마지막 완료 시각이 도달 시각이다")
    void calculate_daily_reachedAtIsLastCompletion() {
        var marks = List.of(onTime(LocalDate.of(2026, 9, 21)), onTime(LocalDate.of(2026, 9, 22)));

        assertThat(calculate(ScheduleType.DAILY, null, marks)).isEqualTo(new AcceptedCount(2, kst(9, 22, 9)));
    }

    @Test @DisplayName("도달 시각은 서비스 시간대(KST) 벽시계를 UTC Instant로 바꿔 싣는다")
    void calculate_reachedAt_convertsKstToUtcInstant() {
        var marks = List.of(new CompletionMark(LocalDate.of(2026, 9, 23), LocalDateTime.of(2026, 9, 23, 9, 0)));

        assertThat(calculate(ScheduleType.DAILY, null, marks).reachedAt())
                .isEqualTo(Instant.parse("2026-09-23T00:00:00Z"));
    }
}
