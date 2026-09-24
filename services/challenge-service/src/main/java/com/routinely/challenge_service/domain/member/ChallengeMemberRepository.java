package com.routinely.challenge_service.domain.member;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ChallengeMemberRepository extends JpaRepository<ChallengeMember, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT m FROM ChallengeMember m WHERE m.challenge.id = :challengeId AND m.userId = :userId")
    Optional<ChallengeMember> findLockedByChallengeIdAndUserId(
            @Param("challengeId") Long challengeId, @Param("userId") Long userId);

    int countByChallengeIdAndStatus(Long challengeId, MembershipStatus status);

    @EntityGraph(attributePaths = "challenge")
    Page<ChallengeMember> findByUserIdAndStatus(Long userId, MembershipStatus status, Pageable pageable);

    Optional<ChallengeMember> findByChallengeIdAndUserIdAndStatus(
            Long challengeId,
            Long userId,
            MembershipStatus status
    );

    Optional<ChallengeMember> findByChallengeIdAndUserId(Long challengeId, Long userId);

    Optional<ChallengeMember> findFirstByChallengeIdAndStatusAndRoleNotOrderByJoinedAtAsc(
            Long challengeId,
            MembershipStatus status,
            ChallengeMemberRole role
    );

    @Query("SELECT m.challenge.id AS challengeId, COUNT(m) AS count " +
            "FROM ChallengeMember m " +
            "WHERE m.challenge.id IN :challengeIds AND m.status = :status " +
            "GROUP BY m.challenge.id")
    List<MemberCountProjection> countMembersByChallengeIdsAndStatus(
            @Param("challengeIds") List<Long> challengeIds,
            @Param("status") MembershipStatus status);
}
