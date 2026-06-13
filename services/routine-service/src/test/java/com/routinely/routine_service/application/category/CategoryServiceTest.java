package com.routinely.routine_service.application.category;

import com.routinely.routine_service.application.category.dto.CategoryResult;
import com.routinely.routine_service.domain.category.Category;
import com.routinely.routine_service.domain.category.CategoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class CategoryServiceTest {

    @Mock
    private CategoryRepository categoryRepository;

    private CategoryService categoryService;

    @BeforeEach
    void setUp() {
        categoryService = new CategoryService(categoryRepository);
    }

    @Test
    @DisplayName("활성 카테고리만 반환된다")
    void listActive_returnsOnlyActiveCategories() {
        Category active1 = Category.builder()
                .code("EXERCISE").name("운동").icon("🏃").displayOrder(1).isActive(true).build();
        Category active2 = Category.builder()
                .code("READING").name("독서").icon("📚").displayOrder(2).isActive(true).build();

        given(categoryRepository.findAllByIsActiveTrueOrderByDisplayOrderAsc())
                .willReturn(List.of(active1, active2));

        List<CategoryResult> results = categoryService.listActive();

        assertThat(results).hasSize(2);
        assertThat(results).extracting(CategoryResult::code)
                .containsExactly("EXERCISE", "READING");
    }

    @Test
    @DisplayName("결과는 displayOrder 오름차순으로 정렬된다")
    void listActive_returnsSortedByDisplayOrder() {
        Category first  = Category.builder()
                .code("EXERCISE").name("운동").icon("🏃").displayOrder(1).isActive(true).build();
        Category second = Category.builder()
                .code("READING").name("독서").icon("📚").displayOrder(2).isActive(true).build();
        Category third  = Category.builder()
                .code("STUDY").name("공부/학습").icon("📖").displayOrder(3).isActive(true).build();

        given(categoryRepository.findAllByIsActiveTrueOrderByDisplayOrderAsc())
                .willReturn(List.of(first, second, third));

        List<CategoryResult> results = categoryService.listActive();

        assertThat(results).hasSize(3);
        assertThat(results.get(0).displayOrder()).isLessThan(results.get(1).displayOrder());
        assertThat(results.get(1).displayOrder()).isLessThan(results.get(2).displayOrder());
    }
}
