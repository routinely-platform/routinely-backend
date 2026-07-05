package com.routinely.challenge_service.infrastructure.redis;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations.TypedTuple;
import org.springframework.stereotype.Repository;

import java.util.Set;

/**
 * 챌린지 랭킹 Redis ZSET 접근 리포지토리.
 *
 * <p>key: {@code ranking:{challengeId}} / member: {@code userId} / score: 달성률(achievement_rate). (ADR-0028)
 * 점수는 증분(+1)이 아니라 절대값으로 갱신(ZADD)하므로, 같은 달성률로 여러 번 덮어써도 결과가 동일하다.
 *
 * <p>조회는 ZREVRANGE/ZCOUNT(달성률 내림차순)로 처리한다.
 * Redis 장애 시 예외를 전파하며, 조회 측은 {@code challenge_member_summary} fallback으로,
 * 갱신 측(스케줄러)은 Inbox 재시도로 최종 일관성을 회복한다.
 */
@Repository
public class ChallengeRankingRedisRepository {

    private static final String KEY_PREFIX = "ranking:";
    public static final double MAX_ACHIEVEMENT_RATE = 100.0;
    private static final double SCORE_EPSILON = 0.000001;

    private final StringRedisTemplate redisTemplate;

    public ChallengeRankingRedisRepository(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /** 달성률을 절대값으로 갱신한다 (ZADD). */
    public void updateScore(Long challengeId, Long userId, double achievementRate) {
        redisTemplate.opsForZSet().add(key(challengeId), String.valueOf(userId), achievementRate);
    }

    /** 멤버를 랭킹에서 제거한다 (ZREM). */
    public void remove(Long challengeId, Long userId) {
        redisTemplate.opsForZSet().remove(key(challengeId), String.valueOf(userId));
    }

    /** 달성률 내림차순 상위 {@code limit}명을 점수와 함께 조회한다 (ZREVRANGE WITHSCORES). */
    public Set<TypedTuple<String>> findTopWithScores(Long challengeId, int limit) {
        return redisTemplate.opsForZSet()
                .reverseRangeWithScores(key(challengeId), 0, limit - 1L);
    }

    /**
     * 점수 구간에 속한 멤버를 조회한다. 동점자 보조 정렬은 DB fallback과 맞추기 위해
     * application layer에서 {@code achievementRate DESC, userId ASC}로 수행한다.
     */
    public Set<TypedTuple<String>> findByScoreGreaterThanOrEqualWithScores(Long challengeId, double min) {
        return redisTemplate.opsForZSet().rangeByScoreWithScores(key(challengeId), min, MAX_ACHIEVEMENT_RATE);
    }

    /** 해당 사용자의 점수 (ZSCORE). 미등록 시 {@code null}. */
    public Double findScore(Long challengeId, Long userId) {
        return redisTemplate.opsForZSet().score(key(challengeId), String.valueOf(userId));
    }

    /**
     * 공동 등수 계산용 — 특정 달성률보다 높은 점수를 가진 멤버 수를 센다.
     * Redis ZCOUNT는 inclusive range라 2자리 달성률보다 작은 epsilon을 더해 동점을 제외한다.
     */
    public long countGreaterThanScore(Long challengeId, double achievementRate) {
        if (achievementRate >= MAX_ACHIEVEMENT_RATE) {
            return 0L;
        }
        Long count = redisTemplate.opsForZSet()
                .count(key(challengeId), achievementRate + SCORE_EPSILON, MAX_ACHIEVEMENT_RATE);
        return count != null ? count : 0L;
    }

    /** 랭킹에 등록된 전체 멤버 수 (ZCARD). */
    public long countMembers(Long challengeId) {
        Long count = redisTemplate.opsForZSet().zCard(key(challengeId));
        return count != null ? count : 0L;
    }

    private String key(Long challengeId) {
        return KEY_PREFIX + challengeId;
    }
}
