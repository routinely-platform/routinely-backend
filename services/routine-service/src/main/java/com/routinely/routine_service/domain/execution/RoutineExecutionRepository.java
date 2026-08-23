package com.routinely.routine_service.domain.execution;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface RoutineExecutionRepository extends JpaRepository<RoutineExecution, Long> {

    /**
     * 루틴+날짜로 저장된 실행 기록을 조회한다. sparse 저장이므로 존재한다면 COMPLETED 행이다.
     * 완료 멱등성 검사와 완료 취소(행 삭제)에 사용한다. {@code uq_re_routine_date} UNIQUE를 활용한다.
     */
    Optional<RoutineExecution> findByRoutineIdAndScheduledDate(Long routineId, LocalDate scheduledDate);

    /**
     * 기간 내 저장된 완료 기록 목록 — routineId가 NULL이면 사용자 전체를 대상으로 한다. sparse 저장상
     * 저장된 행은 COMPLETED뿐이지만 방어적으로 status로도 필터한다. PENDING/MISSED는 저장되지 않으므로
     * 여기서 조회되지 않으며, 조회 계층에서 스케줄 파생으로 채운다. {@code idx_re_user_date}를 활용한다.
     */
    @Query("""
            SELECT e FROM RoutineExecution e
            WHERE e.userId = :userId
              AND e.status = com.routinely.routine_service.domain.execution.ExecutionStatus.COMPLETED
              AND (:routineId IS NULL OR e.routineId = :routineId)
              AND e.scheduledDate BETWEEN :startDate AND :endDate
            """)
    List<RoutineExecution> findCompletedInRange(@Param("userId") Long userId,
                                                @Param("routineId") Long routineId,
                                                @Param("startDate") LocalDate startDate,
                                                @Param("endDate") LocalDate endDate);
}
