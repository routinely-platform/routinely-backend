package com.routinely.routine_service.domain.template;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@DisplayName("RoutineTemplateRepository")
class RoutineTemplateRepositoryTest {

    @Autowired
    private RoutineTemplateRepository routineTemplateRepository;

    private RoutineTemplate savePersonal(Long userId, String categoryCode) {
        return routineTemplateRepository.saveAndFlush(
                RoutineTemplate.forPersonal(userId, "아침 러닝 30분", categoryCode, RepeatType.DAILY, null));
    }

    @Test
    @DisplayName("개인템플릿저장후_미삭제단건조회가된다")
    void findByIdAndIsDeletedFalse_returnsSavedPersonalTemplate() {
        RoutineTemplate saved = savePersonal(1L, "EXERCISE");

        Optional<RoutineTemplate> found = routineTemplateRepository.findByIdAndIsDeletedFalse(saved.getId());

        assertThat(found).isPresent();
        assertThat(found.get().getUserId()).isEqualTo(1L);
        assertThat(found.get().getChallengeId()).isNull();
        assertThat(found.get().isChallengeLinked()).isFalse();
    }

    @Test
    @DisplayName("소프트삭제된템플릿은_미삭제단건조회에서제외된다")
    void findByIdAndIsDeletedFalse_excludesSoftDeleted() {
        RoutineTemplate saved = savePersonal(1L, "EXERCISE");
        saved.softDelete(LocalDateTime.of(2026, 7, 16, 0, 0));
        routineTemplateRepository.saveAndFlush(saved);

        Optional<RoutineTemplate> found = routineTemplateRepository.findByIdAndIsDeletedFalse(saved.getId());

        assertThat(found).isEmpty();
    }

    @Test
    @DisplayName("개인템플릿목록은_본인것만_챌린지연결과삭제분을제외하고_id내림차순으로조회된다")
    void findAllPersonal_excludesChallengeLinkedAndDeletedAndOthers() {
        RoutineTemplate first = savePersonal(1L, "EXERCISE");
        RoutineTemplate second = savePersonal(1L, "READING");
        savePersonal(2L, "EXERCISE"); // 다른 사용자
        routineTemplateRepository.saveAndFlush(RoutineTemplate.forChallenge(
                1L, 42L, "아침 러닝 30분", "EXERCISE", RepeatType.WEEKLY_N, 3)); // 챌린지 연결
        RoutineTemplate deleted = savePersonal(1L, "EXERCISE");
        deleted.softDelete(LocalDateTime.of(2026, 7, 16, 0, 0));
        routineTemplateRepository.saveAndFlush(deleted);

        List<RoutineTemplate> results = routineTemplateRepository
                .findAllByUserIdAndChallengeIdIsNullAndIsDeletedFalseOrderByIdDesc(1L);

        assertThat(results).extracting(RoutineTemplate::getId)
                .containsExactly(second.getId(), first.getId());
    }

    @Test
    @DisplayName("카테고리필터목록조회는_해당카테고리의개인템플릿만반환한다")
    void findAllPersonalByCategory_filtersByCategoryCode() {
        savePersonal(1L, "EXERCISE");
        RoutineTemplate reading = savePersonal(1L, "READING");

        List<RoutineTemplate> results = routineTemplateRepository
                .findAllByUserIdAndChallengeIdIsNullAndCategoryCodeAndIsDeletedFalseOrderByIdDesc(
                        1L, "READING");

        assertThat(results).extracting(RoutineTemplate::getId).containsExactly(reading.getId());
    }
}
