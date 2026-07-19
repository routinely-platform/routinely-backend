package com.routinely.routine_service.domain.category;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CategoryRepository extends JpaRepository<Category, Long> {

    List<Category> findAllByIsActiveTrueOrderByDisplayOrderAsc();

    /**
     * 루틴 템플릿의 category_code 서버 검증용 — 활성 카테고리만 유효한 코드로 인정한다.
     */
    boolean existsByCodeAndIsActiveTrue(String code);
}
