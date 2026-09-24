package com.routinely.challenge_service.domain.summary;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;

public interface ChallengeMemberSummaryRepository extends JpaRepository<ChallengeMemberSummary, Long> {
    Optional<ChallengeMemberSummary> findByChallengeIdAndUserId(Long challengeId, Long userId);

    @Query("""
            SELECT s FROM ChallengeMemberSummary s
            WHERE s.challengeId = :challengeId AND EXISTS (
                SELECT m.id FROM ChallengeMember m WHERE m.challenge.id = s.challengeId
                AND m.userId = s.userId AND m.status = com.routinely.challenge_service.domain.member.MembershipStatus.ACTIVE)
            ORDER BY s.acceptedCount DESC, s.lastCompletedAt ASC NULLS LAST, s.userId ASC
            """)
    List<ChallengeMemberSummary> findActiveRanking(@Param("challengeId") Long challengeId, Pageable pageable);

    @Query("""
            SELECT COUNT(s) FROM ChallengeMemberSummary s
            WHERE s.challengeId = :challengeId AND s.acceptedCount > :score AND EXISTS (
                SELECT m.id FROM ChallengeMember m WHERE m.challenge.id = s.challengeId
                AND m.userId = s.userId AND m.status = com.routinely.challenge_service.domain.member.MembershipStatus.ACTIVE)
            """)
    long countActiveWithHigherScore(@Param("challengeId") Long challengeId, @Param("score") int score);

    List<ChallengeMemberSummary> findByChallengeIdAndUserIdIn(Long challengeId, List<Long> userIds);
}
