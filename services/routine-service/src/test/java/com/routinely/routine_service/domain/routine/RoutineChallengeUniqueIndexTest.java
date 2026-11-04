package com.routinely.routine_service.domain.routine;

import com.routinely.core.schedule.ScheduleType;
import com.routinely.jpa.config.JpaAuditingConfig;
import com.routinely.routine_service.domain.definition.RoutineDefinition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** V8 {@code uq_routines_challenge_user} — 챌린지당 사용자 루틴 1개, 개인 루틴은 제외 (#157). */
@Import(JpaAuditingConfig.class)
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@DisplayName("uq_routines_challenge_user")
class RoutineChallengeUniqueIndexTest {

    private static final RoutineDefinition DEFINITION =
            RoutineDefinition.of("저녁 러닝 30분", "EXERCISE", ScheduleType.DAILY, null, null);
    private static final LocalDate START = LocalDate.of(2026, 11, 10);
    private static final LocalDate END = LocalDate.of(2026, 12, 9);

    @Autowired
    private RoutineRepository routineRepository;

    @Test
    @DisplayName("같은_챌린지에_같은_사용자의_루틴을_두_번_저장하면_거부한다")
    void save_duplicateChallengeUser_throwsException() {
        routineRepository.saveAndFlush(Routine.forChallenge(5L, 100L, DEFINITION, START, END));

        assertThatThrownBy(() -> routineRepository.saveAndFlush(
                Routine.forChallenge(5L, 100L, DEFINITION, START, END)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("같은_챌린지의_다른_사용자와_다른_챌린지의_같은_사용자는_허용한다")
    void save_differentUserOrChallenge_success() {
        routineRepository.saveAndFlush(Routine.forChallenge(5L, 100L, DEFINITION, START, END));

        assertThatCode(() -> {
            routineRepository.saveAndFlush(Routine.forChallenge(5L, 200L, DEFINITION, START, END));
            routineRepository.saveAndFlush(Routine.forChallenge(6L, 100L, DEFINITION, START, END));
        }).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("개인_루틴은_같은_사용자가_여러_개_가져도_제약에_걸리지_않는다")
    void save_personalRoutines_notConstrained() {
        routineRepository.saveAndFlush(Routine.forPersonal(null, 100L, DEFINITION, START, null, null));
        routineRepository.saveAndFlush(Routine.forPersonal(null, 100L, DEFINITION, START, null, null));

        assertThat(routineRepository.findMyRoutines(100L, null, null)).hasSize(2);
    }
}
