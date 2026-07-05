package com.routinely.challenge_service.domain.summary;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

public interface ChallengeMemberSummaryRepository extends JpaRepository<ChallengeMemberSummary, Long> {

    Optional<ChallengeMemberSummary> findByChallengeIdAndUserId(Long challengeId, Long userId);

    /**
     * 달성률 내림차순 랭킹 — Redis ZSET 미스 시 fallback 조회.
     * {@code idx_cms_challenge_rate (challenge_id, achievement_rate DESC)} 인덱스를 활용한다.
     * 동점은 user_id 오름차순으로 안정 정렬한다.
     */
    List<ChallengeMemberSummary> findByChallengeIdOrderByAchievementRateDescUserIdAsc(
            Long challengeId, Pageable pageable);

    /**
     * 챌린지 전체 집계 인원 수 — ZSET(ZCARD) 미스/장애 시 fallback.
     * 상위 목록(limit으로 잘린)의 크기를 그대로 쓰면 전체 인원보다 작게 나올 수 있어 별도로 COUNT한다.
     */
    long countByChallengeId(Long challengeId);

    /**
     * 공동 등수 계산용 — 특정 달성률보다 높은 점수를 가진 멤버 수를 센다.
     * 내 등수는 {@code count + 1}로 계산한다.
     */
    long countByChallengeIdAndAchievementRateGreaterThan(Long challengeId, BigDecimal achievementRate);
}
