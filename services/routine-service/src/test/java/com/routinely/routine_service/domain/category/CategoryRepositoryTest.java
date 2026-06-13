package com.routinely.routine_service.domain.category;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.test.context.ActiveProfiles;

import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@DisplayName("CategoryRepository")
class CategoryRepositoryTest {

    @Autowired
    private CategoryRepository categoryRepository;

    @Test
    @DisplayName("활성 카테고리만 조회되고 비활성은 제외된다")
    void findActive_excludesInactiveCategories() {
        Category inactive = Category.builder()
                .code("INACTIVE_TEST")
                .name("비활성 테스트")
                .icon("🚫")
                .displayOrder(99)
                .isActive(false)
                .build();
        categoryRepository.saveAndFlush(inactive);

        List<Category> result = categoryRepository.findAllByIsActiveTrueOrderByDisplayOrderAsc();

        assertThat(result).isNotEmpty();
        assertThat(result).allMatch(Category::isActive);
        assertThat(result).extracting(Category::getCode).doesNotContain("INACTIVE_TEST");
    }

    @Test
    @DisplayName("V2 시드 12개가 displayOrder 오름차순으로 조회된다")
    void findActive_returnsSeededCategoriesSortedByDisplayOrder() {
        List<Category> result = categoryRepository.findAllByIsActiveTrueOrderByDisplayOrderAsc();

        assertThat(result).hasSize(12);
        assertThat(result).extracting(Category::getCode).containsExactly(
                "EXERCISE", "READING", "STUDY", "LANGUAGE", "HEALTH", "SLEEP",
                "DIET", "MEDITATION", "SELF_IMPROVE", "PRODUCTIVITY", "HOBBY", "QUIT_HABIT"
        );
        assertThat(result).isSortedAccordingTo(Comparator.comparingInt(Category::getDisplayOrder));
    }
}
