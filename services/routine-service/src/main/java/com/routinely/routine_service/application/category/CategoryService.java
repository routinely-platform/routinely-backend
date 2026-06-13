package com.routinely.routine_service.application.category;

import com.routinely.routine_service.application.category.dto.CategoryResult;
import com.routinely.routine_service.domain.category.CategoryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Transactional(readOnly = true)
public class CategoryService {

    private final CategoryRepository categoryRepository;

    public CategoryService(CategoryRepository categoryRepository) {
        this.categoryRepository = categoryRepository;
    }

    public List<CategoryResult> listActive() {
        return categoryRepository.findAllByIsActiveTrueOrderByDisplayOrderAsc()
                .stream()
                .map(CategoryResult::from)
                .toList();
    }
}
