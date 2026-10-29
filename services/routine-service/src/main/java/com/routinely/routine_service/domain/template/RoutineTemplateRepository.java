package com.routinely.routine_service.domain.template;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RoutineTemplateRepository extends JpaRepository<RoutineTemplate, Long> {

    Optional<RoutineTemplate> findByIdAndIsDeletedFalse(Long id);

    /**
     * 개인 템플릿 목록 — 삭제된 템플릿은 제외한다.
     * {@code idx_rt_user_id} 부분 인덱스(WHERE is_deleted = false)를 활용한다.
     */
    List<RoutineTemplate> findAllByUserIdAndIsDeletedFalseOrderByIdDesc(Long userId);

    /**
     * 정의가 {@code RoutineDefinition} 임베더블로 묶여 있어 경로가 {@code definition.categoryCode} 다.
     * 메서드명의 {@code Definition} 조각이 그 중첩을 가리킨다 (ADR-0040).
     */
    List<RoutineTemplate> findAllByUserIdAndDefinitionCategoryCodeAndIsDeletedFalseOrderByIdDesc(
            Long userId, String categoryCode);
}
