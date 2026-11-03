package com.routinely.challenge_service.domain.challenge;

import com.routinely.core.exception.BusinessException;
import com.routinely.core.schedule.ScheduleType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ChallengeRoutineDefinition — ck_challenges_schedule의 코드 대응물")
class ChallengeRoutineDefinitionTest {

    @ParameterizedTest(name = "{0} / days={1} / count={2}")
    @CsvSource({
            "DAILY,         ,   ",
            "SPECIFIC_DAYS, 1,  ",
            "SPECIFIC_DAYS, 21, ",
            "SPECIFIC_DAYS, 127,",
            "WEEKLY_COUNT,  ,  1",
            "WEEKLY_COUNT,  ,  6",
            "MONTHLY_COUNT, ,  1",
            "MONTHLY_COUNT, , 28"
    })
    @DisplayName("CHECK가 허용하는 조합으로는 정의가 만들어진다")
    void of_validCombination_created(ScheduleType scheduleType, Short daysOfWeek, Integer targetCount) {
        ChallengeRoutineDefinition definition =
                ChallengeRoutineDefinition.of("아침 러닝", scheduleType, daysOfWeek, targetCount);

        assertThat(definition.getRoutineTitle()).isEqualTo("아침 러닝");
        assertThat(definition.getScheduleType()).isEqualTo(scheduleType);
        assertThat(definition.getDaysOfWeek()).isEqualTo(daysOfWeek);
        assertThat(definition.getTargetCount()).isEqualTo(targetCount);
    }

    @ParameterizedTest(name = "{0} / days={1} / count={2}")
    @CsvSource({
            "DAILY,        1,   ",
            "DAILY,        0,   ",
            "DAILY,         ,  1",
            "SPECIFIC_DAYS,  ,  ",
            "SPECIFIC_DAYS, 0,  ",
            "SPECIFIC_DAYS, 128,",
            "SPECIFIC_DAYS, 21, 1",
            "WEEKLY_COUNT,  ,  0",
            "WEEKLY_COUNT,  ,  7",
            "WEEKLY_COUNT,  ,   ",
            "WEEKLY_COUNT, 1,  3",
            "MONTHLY_COUNT, ,  0",
            "MONTHLY_COUNT, , 29",
            "MONTHLY_COUNT, ,   ",
            "MONTHLY_COUNT, 1, 3"
    })
    @DisplayName("CHECK가 거부하는 조합으로는 정의가 만들어지지 않는다")
    void of_invalidCombination_throwsException(ScheduleType scheduleType, Short daysOfWeek, Integer targetCount) {
        assertThatThrownBy(() -> ChallengeRoutineDefinition.of("아침 러닝", scheduleType, daysOfWeek, targetCount))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("반복 유형이 없으면 정의가 만들어지지 않는다")
    void of_nullScheduleType_throwsException() {
        assertThatThrownBy(() -> ChallengeRoutineDefinition.of("아침 러닝", null, null, null))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("제목을 바꾸면 스케줄은 그대로인 새 정의가 만들어진다")
    void withRoutineTitle_keepsSchedule() {
        ChallengeRoutineDefinition original =
                ChallengeRoutineDefinition.of("아침 러닝", ScheduleType.SPECIFIC_DAYS, (short) 21, null);

        ChallengeRoutineDefinition renamed = original.withRoutineTitle("저녁 러닝");

        assertThat(renamed).isNotSameAs(original);
        assertThat(renamed.getRoutineTitle()).isEqualTo("저녁 러닝");
        assertThat(renamed.getScheduleType()).isEqualTo(ScheduleType.SPECIFIC_DAYS);
        assertThat(renamed.getDaysOfWeek()).isEqualTo((short) 21);
        assertThat(original.getRoutineTitle()).isEqualTo("아침 러닝");
    }
}
