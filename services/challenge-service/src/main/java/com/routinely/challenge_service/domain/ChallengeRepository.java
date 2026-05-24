package com.routinely.challenge_service.domain;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;

public interface ChallengeRepository extends JpaRepository<Challenge, Long> {

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
