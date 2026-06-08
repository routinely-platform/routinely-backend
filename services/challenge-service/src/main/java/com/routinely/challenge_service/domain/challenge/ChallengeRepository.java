package com.routinely.challenge_service.domain.challenge;

import com.routinely.challenge_service.domain.member.MembershipStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ChallengeRepository extends JpaRepository<Challenge, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM Challenge c WHERE c.id = :challengeId")
    Optional<Challenge> findByIdForUpdate(@Param("challengeId") Long challengeId);

    @Query("SELECT c FROM Challenge c WHERE c.status = 'WAITING' AND c.startedAt <= :today")
    List<Challenge> findWaitingToActivate(@Param("today") LocalDate today);

    @Query("SELECT c FROM Challenge c WHERE c.status = 'ACTIVE' AND c.endedAt < :today")
    List<Challenge> findActiveToEnd(@Param("today") LocalDate today);

    @Query("""
            SELECT c
            FROM Challenge c
            WHERE c.isPublic = true
              AND c.status = :status
              AND NOT EXISTS (
                  SELECT 1
                  FROM ChallengeMember m
                  WHERE m.challenge = c
                    AND m.userId = :userId
                    AND m.status IN :excludedStatuses
              )
            """)
    Page<Challenge> findJoinablePublicChallenges(@Param("userId") Long userId,
                                                 @Param("status") ChallengeLifecycleStatus status,
                                                 @Param("excludedStatuses") Collection<MembershipStatus> excludedStatuses,
                                                 Pageable pageable);
}
