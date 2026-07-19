package com.routinely.routine_service.domain.routine;

import com.routinely.routine_service.domain.template.RepeatType;
import com.routinely.routine_service.domain.template.RoutineTemplate;
import com.routinely.routine_service.domain.template.RoutineTemplateRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@DisplayName("RoutineRepository")
class RoutineRepositoryTest {

    private static final LocalDate START = LocalDate.of(2026, 2, 1);
    private static final LocalDate END = LocalDate.of(2026, 3, 2);

    @Autowired
    private RoutineRepository routineRepository;

    @Autowired
    private RoutineTemplateRepository templateRepository;

    private Long savePersonalTemplate(Long userId) {
        RoutineTemplate template = templateRepository.saveAndFlush(
                RoutineTemplate.forPersonal(userId, "아침 러닝 30분", "EXERCISE", RepeatType.DAILY, null));
        return template.getId();
    }

    private Routine saveRoutine(Long templateId, Long userId, boolean active, Long challengeId) {
        Routine routine = Routine.forPersonal(templateId, userId, START, END, LocalTime.of(7, 0));
        if (!active) {
            routine.deactivate();
        }
        if (challengeId != null) {
            ReflectionTestUtils.setField(routine, "challengeId", challengeId);
        }
        return routineRepository.saveAndFlush(routine);
    }

    @Test
    @DisplayName("루틴저장후_소유자기준단건조회가된다")
    void findByIdAndUserId_returnsOwnedRoutine() {
        Long templateId = savePersonalTemplate(1L);
        Routine saved = saveRoutine(templateId, 1L, true, null);

        Optional<Routine> found = routineRepository.findByIdAndUserId(saved.getId(), 1L);

        assertThat(found).isPresent();
        assertThat(found.get().getRoutineTemplateId()).isEqualTo(templateId);
        assertThat(found.get().isActive()).isTrue();
    }

    @Test
    @DisplayName("소유자가다르면_단건조회에서제외된다")
    void findByIdAndUserId_whenNotOwner_returnsEmpty() {
        Long templateId = savePersonalTemplate(1L);
        Routine saved = saveRoutine(templateId, 1L, true, null);

        Optional<Routine> found = routineRepository.findByIdAndUserId(saved.getId(), 2L);

        assertThat(found).isEmpty();
    }

    @Test
    @DisplayName("필터가없으면_본인루틴전체를id내림차순으로조회한다")
    void findMyRoutines_withoutFilter_returnsAllOwnedDescending() {
        Long templateId = savePersonalTemplate(1L);
        Routine first = saveRoutine(templateId, 1L, true, null);
        Routine second = saveRoutine(templateId, 1L, false, null);
        saveRoutine(templateId, 2L, true, null); // 다른 사용자

        List<Routine> results = routineRepository.findMyRoutines(1L, null, null);

        assertThat(results).extracting(Routine::getId)
                .containsExactly(second.getId(), first.getId());
    }

    @Test
    @DisplayName("isActive필터는_해당활성상태의루틴만조회한다")
    void findMyRoutines_withIsActiveFilter_returnsMatchingOnly() {
        Long templateId = savePersonalTemplate(1L);
        Routine active = saveRoutine(templateId, 1L, true, null);
        saveRoutine(templateId, 1L, false, null);

        List<Routine> results = routineRepository.findMyRoutines(1L, true, null);

        assertThat(results).extracting(Routine::getId).containsExactly(active.getId());
    }

    @Test
    @DisplayName("challengeId필터는_해당챌린지의루틴만조회한다")
    void findMyRoutines_withChallengeIdFilter_returnsMatchingOnly() {
        Long templateId = savePersonalTemplate(1L);
        saveRoutine(templateId, 1L, true, null); // 개인 루틴
        Routine challengeRoutine = saveRoutine(templateId, 1L, true, 42L);

        List<Routine> results = routineRepository.findMyRoutines(1L, null, 42L);

        assertThat(results).extracting(Routine::getId).containsExactly(challengeRoutine.getId());
    }
}
