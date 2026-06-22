package com.routinely.routine_service.domain.template;

import org.springframework.data.jpa.repository.JpaRepository;

public interface RoutineTemplateRepository extends JpaRepository<RoutineTemplate, Long> {

    /**
     * 챌린지 템플릿 멱등성 — 동일 challengeId의 템플릿이 이미 존재하면 재생성하지 않는다.
     * 스케줄러 재시도 및 중복 처리에 대비한다.
     */
    boolean existsByChallengeId(Long challengeId);
}
