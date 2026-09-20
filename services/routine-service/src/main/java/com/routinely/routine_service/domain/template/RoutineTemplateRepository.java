package com.routinely.routine_service.domain.template;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RoutineTemplateRepository extends JpaRepository<RoutineTemplate, Long> {

    /**
     * 챌린지 템플릿 멱등성 — 동일 challengeId의 템플릿이 이미 존재하면 재생성하지 않는다.
     * 스케줄러 재시도 및 중복 처리에 대비한다.
     */
    boolean existsByChallengeId(Long challengeId);

    Optional<RoutineTemplate> findByIdAndIsDeletedFalse(Long id);

    /**
     * 개인 템플릿 목록 — 챌린지 연결 템플릿(challenge_id NOT NULL)과 삭제된 템플릿은 제외한다.
     * {@code idx_rt_user_id} 부분 인덱스(WHERE is_deleted = false)를 활용한다.
     */
    List<RoutineTemplate> findAllByUserIdAndChallengeIdIsNullAndIsDeletedFalseOrderByIdDesc(Long userId);

    /**
     * 정의가 {@code RoutineDefinition} 임베더블로 묶여 있어 경로가 {@code definition.categoryCode} 다.
     * 메서드명의 {@code Definition} 조각이 그 중첩을 가리킨다 (ADR-0040).
     */
    List<RoutineTemplate> findAllByUserIdAndChallengeIdIsNullAndDefinitionCategoryCodeAndIsDeletedFalseOrderByIdDesc(
            Long userId, String categoryCode);
}
