package com.routinely.routine_service.application.execution;

import com.routinely.routine_service.domain.execution.ExecutionStatus;
import com.routinely.routine_service.domain.template.RoutineTemplate;
import com.routinely.routine_service.domain.template.ScheduleType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ExecutionScheduleDeriver")
class ExecutionScheduleDeriverTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 7, 24); // 금요일
    private static final LocalDate YESTERDAY = TODAY.minusDays(1);
    private static final LocalDate TOMORROW = TODAY.plusDays(1);

    private static RoutineTemplate template(ScheduleType type, Short days, Integer count) {
        return RoutineTemplate.forPersonal(1L, "러닝", "EXERCISE", type, days, count);
    }

    /** 해당 날짜의 요일을 포함하는 비트마스크. */
    private static short maskOf(LocalDate date) {
        return (short) (1 << (date.getDayOfWeek().getValue() - 1));
    }

    /** 해당 날짜의 요일을 포함하지 않는 비트마스크. */
    private static short maskExcluding(LocalDate date) {
        return (short) (1 << (date.getDayOfWeek().getValue() % 7));
    }

    @Nested
    @DisplayName("DAILY")
    class Daily {
        private final RoutineTemplate t = template(ScheduleType.DAILY, null, null);

        @Test
        @DisplayName("오늘은_PENDING_과거는_MISSED_미래는_PENDING")
        void derivesEveryDay() {
            assertThat(ExecutionScheduleDeriver.deriveStatus(t, TODAY, TODAY)).contains(ExecutionStatus.PENDING);
            assertThat(ExecutionScheduleDeriver.deriveStatus(t, YESTERDAY, TODAY)).contains(ExecutionStatus.MISSED);
            assertThat(ExecutionScheduleDeriver.deriveStatus(t, TOMORROW, TODAY)).contains(ExecutionStatus.PENDING);
        }

        @Test
        @DisplayName("아무날이나_수행가능하다")
        void completableAnyDay() {
            assertThat(ExecutionScheduleDeriver.isCompletable(t, TODAY)).isTrue();
        }
    }

    @Nested
    @DisplayName("SPECIFIC_DAYS")
    class SpecificDays {

        @Test
        @DisplayName("지정요일이면_오늘PENDING_과거MISSED_미래PENDING")
        void dueDay_derivesLikeObligation() {
            RoutineTemplate today = template(ScheduleType.SPECIFIC_DAYS, maskOf(TODAY), null);
            RoutineTemplate yest = template(ScheduleType.SPECIFIC_DAYS, maskOf(YESTERDAY), null);
            RoutineTemplate tom = template(ScheduleType.SPECIFIC_DAYS, maskOf(TOMORROW), null);

            assertThat(ExecutionScheduleDeriver.deriveStatus(today, TODAY, TODAY)).contains(ExecutionStatus.PENDING);
            assertThat(ExecutionScheduleDeriver.deriveStatus(yest, YESTERDAY, TODAY)).contains(ExecutionStatus.MISSED);
            assertThat(ExecutionScheduleDeriver.deriveStatus(tom, TOMORROW, TODAY)).contains(ExecutionStatus.PENDING);
        }

        @Test
        @DisplayName("지정요일이아니면_어떤날도_표시하지않는다")
        void offDay_derivesNothing() {
            RoutineTemplate today = template(ScheduleType.SPECIFIC_DAYS, maskExcluding(TODAY), null);
            RoutineTemplate yest = template(ScheduleType.SPECIFIC_DAYS, maskExcluding(YESTERDAY), null);
            RoutineTemplate tom = template(ScheduleType.SPECIFIC_DAYS, maskExcluding(TOMORROW), null);

            assertThat(ExecutionScheduleDeriver.deriveStatus(today, TODAY, TODAY)).isEmpty();
            assertThat(ExecutionScheduleDeriver.deriveStatus(yest, YESTERDAY, TODAY)).isEmpty();
            assertThat(ExecutionScheduleDeriver.deriveStatus(tom, TOMORROW, TODAY)).isEmpty();
        }

        @Test
        @DisplayName("지정요일만_수행가능하다")
        void completableOnlyOnDueDay() {
            assertThat(ExecutionScheduleDeriver.isCompletable(
                    template(ScheduleType.SPECIFIC_DAYS, maskOf(TODAY), null), TODAY)).isTrue();
            assertThat(ExecutionScheduleDeriver.isCompletable(
                    template(ScheduleType.SPECIFIC_DAYS, maskExcluding(TODAY), null), TODAY)).isFalse();
        }
    }

    @Nested
    @DisplayName("빈도형(WEEKLY_COUNT/MONTHLY_COUNT)")
    class CountTypes {

        @Test
        @DisplayName("오늘만_PENDING이고_과거미래는_표시하지않는다(결석없음)")
        void todayPending_pastFutureOmitted() {
            for (ScheduleType type : new ScheduleType[]{ScheduleType.WEEKLY_COUNT, ScheduleType.MONTHLY_COUNT}) {
                RoutineTemplate t = template(type, null, 3);
                assertThat(ExecutionScheduleDeriver.deriveStatus(t, TODAY, TODAY))
                        .as("%s 오늘", type).contains(ExecutionStatus.PENDING);
                assertThat(ExecutionScheduleDeriver.deriveStatus(t, YESTERDAY, TODAY))
                        .as("%s 과거", type).isEmpty();
                assertThat(ExecutionScheduleDeriver.deriveStatus(t, TOMORROW, TODAY))
                        .as("%s 미래", type).isEmpty();
            }
        }

        @Test
        @DisplayName("아무날이나_수행가능하다")
        void completableAnyDay() {
            assertThat(ExecutionScheduleDeriver.isCompletable(
                    template(ScheduleType.WEEKLY_COUNT, null, 3), YESTERDAY)).isTrue();
        }
    }

    @Test
    @DisplayName("빈도형_과거미완료는_Optional_empty로_결석이아님을보인다")
    void countType_pastIsNotMissed() {
        Optional<ExecutionStatus> status = ExecutionScheduleDeriver.deriveStatus(
                template(ScheduleType.MONTHLY_COUNT, null, 2), YESTERDAY, TODAY);
        assertThat(status).isEmpty();
    }
}
