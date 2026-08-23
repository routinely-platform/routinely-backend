package com.routinely.routine_service.domain.routine;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface RoutineRepository extends JpaRepository<Routine, Long> {

    Optional<Routine> findByIdAndUserId(Long id, Long userId);

    /**
     * 내 루틴 목록 — 활성 여부(isActive)와 챌린지(challengeId)로 선택 필터링한다.
     * 각 파라미터가 NULL이면 해당 조건을 건너뛴다. 최신 생성 순(id 내림차순) —
     * startedAt은 사용자가 과거/미래로 지정할 수 있어 시작일이 아닌 생성 순으로 정렬한다.
     */
    @Query("""
            SELECT r FROM Routine r
            WHERE r.userId = :userId
              AND (:isActive IS NULL OR r.isActive = :isActive)
              AND (:challengeId IS NULL OR r.challengeId = :challengeId)
            ORDER BY r.id DESC
            """)
    List<Routine> findMyRoutines(@Param("userId") Long userId,
                                 @Param("isActive") Boolean isActive,
                                 @Param("challengeId") Long challengeId);

    /**
     * 실행 기록 조회 대상 루틴 — 수행 기간([started_at, ended_at])이 조회 기간과 겹치는 본인 루틴을 모은다.
     * routineId가 NULL이면 사용자 전체를 대상으로 한다. 중단(비활성) 루틴도 <b>완료 이력</b>을 보여주기 위해
     * 포함하되, PENDING/MISSED 파생 대상에서는 서비스 계층이 제외한다. (기간 겹침: started_at ≤ endDate
     * AND ended_at ≥ startDate)
     */
    @Query("""
            SELECT r FROM Routine r
            WHERE r.userId = :userId
              AND (:routineId IS NULL OR r.id = :routineId)
              AND r.startedAt <= :endDate
              AND r.endedAt >= :startDate
            ORDER BY r.id DESC
            """)
    List<Routine> findForExecutionDerivation(@Param("userId") Long userId,
                                             @Param("routineId") Long routineId,
                                             @Param("startDate") LocalDate startDate,
                                             @Param("endDate") LocalDate endDate);
}
