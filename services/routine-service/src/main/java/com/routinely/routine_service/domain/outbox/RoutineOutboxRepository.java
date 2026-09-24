package com.routinely.routine_service.domain.outbox;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface RoutineOutboxRepository extends JpaRepository<RoutineOutbox, Long> {

    @Query(value = """
            SELECT * FROM routine_outbox
            WHERE status = 'PENDING'
            ORDER BY created_at ASC, id ASC
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<RoutineOutbox> findPendingForUpdate(@Param("limit") int limit);
    @Query(value = "SELECT nextval('routine_event_revision_seq')", nativeQuery = true)
    long nextRevision();
}
