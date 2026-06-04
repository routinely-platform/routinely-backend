package com.routinely.challenge_service.domain.member;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ChallengeMemberRepository extends JpaRepository<ChallengeMember, Long> {

    int countByChallengeIdAndStatus(Long challengeId, MembershipStatus status);

    @EntityGraph(attributePaths = "challenge")
    Page<ChallengeMember> findByUserIdAndStatus(Long userId, MembershipStatus status, Pageable pageable);

    Optional<ChallengeMember> findByChallengeIdAndUserIdAndStatus(
            Long challengeId,
            Long userId,
            MembershipStatus status
    );

    @Query("SELECT m.challenge.id AS challengeId, COUNT(m) AS count " +
            "FROM ChallengeMember m " +
            "WHERE m.challenge.id IN :challengeIds AND m.status = :status " +
            "GROUP BY m.challenge.id")
    List<MemberCountProjection> countMembersByChallengeIdsAndStatus(
            @Param("challengeIds") List<Long> challengeIds,
            @Param("status") MembershipStatus status);
}
