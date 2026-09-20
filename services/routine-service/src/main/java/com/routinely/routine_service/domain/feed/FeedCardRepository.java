package com.routinely.routine_service.domain.feed;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;
public interface FeedCardRepository extends JpaRepository<FeedCard, Long> {
    Optional<FeedCard> findByRoutineExecutionId(Long executionId);
    List<FeedCard> findByRoutineExecutionIdIn(List<Long> executionIds);
}
