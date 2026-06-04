package com.routinely.challenge_service.domain.outbox;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ChallengeOutboxRepository extends JpaRepository<ChallengeOutbox, Long> {

    @Query(value = """
            SELECT * FROM challenge_outbox
            WHERE status = 'PENDING'
            ORDER BY created_at ASC, id ASC
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<ChallengeOutbox> findPendingForUpdate(@Param("limit") int limit);
}
