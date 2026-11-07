package com.routinely.routine_service.domain.routine;

import com.routinely.core.schedule.ScheduleType;
import com.routinely.routine_service.domain.definition.RoutineDefinition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Routine.forChallenge")
class RoutineForChallengeTest {

    private static final RoutineDefinition DEFINITION =
            RoutineDefinition.of("저녁 러닝 30분", "EXERCISE", ScheduleType.SPECIFIC_DAYS, (short) 21, null);
    private static final LocalDate START = LocalDate.of(2026, 11, 10);
    private static final LocalDate END = LocalDate.of(2026, 12, 9);

    @Test
    @DisplayName("챌린지_루틴은_템플릿·선호_설정_없이_기간과_챌린지를_갖고_활성으로_시작한다")
    void forChallenge_success() {
        Routine routine = Routine.forChallenge(5L, 100L, DEFINITION, START, END);

        assertThat(routine.getChallengeId()).isEqualTo(5L);
        assertThat(routine.getUserId()).isEqualTo(100L);
        assertThat(routine.getRoutineTemplateId()).isNull();
        assertThat(routine.getPreferredTime()).isNull();
        assertThat(routine.getPreferredDays()).isNull();
        assertThat(routine.getStartedAt()).isEqualTo(START);
        assertThat(routine.getEndedAt()).isEqualTo(END);
        assertThat(routine.isActive()).isTrue();
        assertThat(routine.isChallengeRoutine()).isTrue();
        assertThat(routine.hasNoEndDate()).isFalse();
    }

    @Test
    @DisplayName("종료일이_없으면_챌린지_루틴을_만들지_않는다")
    void forChallenge_withoutEndedAt_throwsException() {
        assertThatThrownBy(() -> Routine.forChallenge(5L, 100L, DEFINITION, START, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("종료일");
    }

    @Test
    @DisplayName("챌린지_ID가_없으면_챌린지_루틴을_만들지_않는다")
    void forChallenge_withoutChallengeId_throwsException() {
        assertThatThrownBy(() -> Routine.forChallenge(null, 100L, DEFINITION, START, END))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("challengeId");
    }
}
