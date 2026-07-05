package com.routinely.challenge_service.domain.inbox;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ChallengeInboxRepository extends JpaRepository<ChallengeInbox, Long> {

    /**
     * 중복 수신 여부 판별 — UNIQUE(message_id) 위반 전에 멱등성을 보장한다. (ADR-0013)
     */
    boolean existsByMessageId(String messageId);

    /**
     * 스케줄러 처리 대상(RECEIVED) ID를 오래된 순으로 조회한다.
     * {@code idx_ci_status} 부분 인덱스(WHERE status = 'RECEIVED')를 활용한다.
     */
    @Query("SELECT i.id FROM ChallengeInbox i WHERE i.status = :status ORDER BY i.id ASC")
    List<Long> findIdsByStatus(@Param("status") InboxStatus status, Pageable pageable);
}
