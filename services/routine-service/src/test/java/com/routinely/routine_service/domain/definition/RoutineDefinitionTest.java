package com.routinely.routine_service.domain.definition;

import com.routinely.core.exception.BusinessException;
import com.routinely.core.exception.ErrorCode;
import com.routinely.routine_service.domain.template.ScheduleType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code ck_rt_schedule} / {@code ck_routines_schedule} 두 CHECK 제약이 코드에서 지켜지는지 고정한다.
 *
 * <p>ADR-0040이 "두 제약은 짝이고 컴파일러가 잡지 못하는 결합"이라고 경고한 지점이다.
 * 정의를 값 객체로 모아 <b>잘못된 조합으로는 객체가 만들어지지 않게</b> 했으므로,
 * 그 성질이 유지되는지를 여기서 지킨다.
 */
@DisplayName("RoutineDefinition")
class RoutineDefinitionTest {

    private static RoutineDefinition of(ScheduleType type, Short days, Integer count) {
        return RoutineDefinition.of("아침 러닝", "EXERCISE", type, days, count);
    }

    @Nested
    @DisplayName("스케줄 정합성 — 잘못된 조합은 객체가 되지 않는다")
    class ScheduleInvariant {

        @Test
        @DisplayName("DAILY는_요일과목표횟수를가질수없다")
        void daily_withDaysOrCount_throws() {
            assertThatCode(() -> of(ScheduleType.DAILY, null, null)).doesNotThrowAnyException();

            assertThatThrownBy(() -> of(ScheduleType.DAILY, (short) 21, null))
                    .isInstanceOfSatisfying(BusinessException.class, e ->
                            assertThat(e.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
            assertThatThrownBy(() -> of(ScheduleType.DAILY, null, 3))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("SPECIFIC_DAYS는_요일이필수이고_목표횟수를가질수없다")
        void specificDays_requiresDays_rejectsCount() {
            assertThatCode(() -> of(ScheduleType.SPECIFIC_DAYS, (short) 21, null)).doesNotThrowAnyException();

            assertThatThrownBy(() -> of(ScheduleType.SPECIFIC_DAYS, null, null))
                    .isInstanceOf(BusinessException.class);
            assertThatThrownBy(() -> of(ScheduleType.SPECIFIC_DAYS, (short) 21, 3))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("요일비트마스크는_1에서127사이여야한다")
        void specificDays_maskOutOfRange_throws() {
            // 0은 DB에서 NULL이 아니라 "빈 집합"이라 CHECK에 걸린다. 128 이상은 월~일 7비트를 벗어난다.
            assertThatThrownBy(() -> of(ScheduleType.SPECIFIC_DAYS, (short) 0, null))
                    .isInstanceOf(BusinessException.class);
            assertThatThrownBy(() -> of(ScheduleType.SPECIFIC_DAYS, (short) 128, null))
                    .isInstanceOf(BusinessException.class);
            assertThatCode(() -> of(ScheduleType.SPECIFIC_DAYS, (short) 127, null)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("빈도형은_1이상의목표횟수가필수이고_요일을가질수없다")
        void countTypes_requireCount_rejectDays() {
            assertThatCode(() -> of(ScheduleType.WEEKLY_COUNT, null, 3)).doesNotThrowAnyException();
            assertThatCode(() -> of(ScheduleType.MONTHLY_COUNT, null, 12)).doesNotThrowAnyException();

            assertThatThrownBy(() -> of(ScheduleType.WEEKLY_COUNT, null, null))
                    .isInstanceOf(BusinessException.class);
            assertThatThrownBy(() -> of(ScheduleType.WEEKLY_COUNT, null, 0))
                    .isInstanceOf(BusinessException.class);
            assertThatThrownBy(() -> of(ScheduleType.MONTHLY_COUNT, (short) 21, 3))
                    .isInstanceOf(BusinessException.class);
        }
    }

    @Nested
    @DisplayName("복사·변경")
    class CopyAndChange {

        @Test
        @DisplayName("복사본은_모든필드가같다")
        void copy_preservesEveryField() {
            RoutineDefinition origin = of(ScheduleType.SPECIFIC_DAYS, (short) 21, null);
            RoutineDefinition copied = origin.copy();

            assertThat(copied).usingRecursiveComparison().isEqualTo(origin);
        }

        @Test
        @DisplayName("변경은_새인스턴스를만들고_원본을건드리지않는다")
        void withTitle_doesNotMutateOrigin() {
            RoutineDefinition origin = of(ScheduleType.DAILY, null, null);
            RoutineDefinition changed = origin.withTitle("저녁 산책");

            assertThat(changed.getTitle()).isEqualTo("저녁 산책");
            assertThat(origin.getTitle()).isEqualTo("아침 러닝");
        }

        @Test
        @DisplayName("스케줄변경도_정합성검사를지나간다")
        void withSchedule_revalidates() {
            RoutineDefinition daily = of(ScheduleType.DAILY, null, null);

            assertThatCode(() -> daily.withSchedule(ScheduleType.WEEKLY_COUNT, null, 3))
                    .doesNotThrowAnyException();
            // 유형만 바꾸고 딸린 값을 안 넘기면 거부된다 — 세 값은 항상 함께 간다.
            assertThatThrownBy(() -> daily.withSchedule(ScheduleType.WEEKLY_COUNT, null, null))
                    .isInstanceOf(BusinessException.class);
        }
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"WEEKLY_COUNT,6", "MONTHLY_COUNT,28"})
    @DisplayName("값 객체는 상한까지 허용하고 상한을 넘는 목표는 차단한다")
    void of_targetUpperBounds(ScheduleType type, int max) {
        assertThatCode(() -> RoutineDefinition.of("루틴", "HEALTH", type, null, max)).doesNotThrowAnyException();
        assertThatThrownBy(() -> RoutineDefinition.of("루틴", "HEALTH", type, null, max + 1))
                .isInstanceOf(BusinessException.class);
    }
}
