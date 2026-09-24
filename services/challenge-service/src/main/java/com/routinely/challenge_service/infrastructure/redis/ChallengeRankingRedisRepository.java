package com.routinely.challenge_service.infrastructure.redis;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations.TypedTuple;
import org.springframework.stereotype.Repository;

import java.util.Set;

/**
 * 챌린지 랭킹 Redis ZSET 접근 리포지토리.
 *
 * <p>key: {@code ranking:{challengeId}} / member: {@code userId} / score: 인정 횟수(accepted_count). (ADR-0028)
 * 점수는 증분(+1)이 아니라 절대값으로 갱신(ZADD)하므로, 같은 인정 횟수로 여러 번 덮어써도 결과가 동일하다.
 *
 * <p>조회는 ZREVRANGE/ZCOUNT(인정 횟수 내림차순)로 처리한다.
 * Redis 장애 시 예외를 전파하며, 조회 측은 {@code challenge_member_summary} fallback으로,
 * 갱신 측(스케줄러)은 Inbox 재시도로 최종 일관성을 회복한다.
 */
@Repository
public class ChallengeRankingRedisRepository {

    private static final String KEY_PREFIX = "ranking:";

    private final StringRedisTemplate redisTemplate;

    public ChallengeRankingRedisRepository(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /** 인정 횟수을 절대값으로 갱신한다 (ZADD). */
    public void updateScore(Long challengeId, Long userId, double acceptedCount) {
        redisTemplate.opsForZSet().add(key(challengeId), String.valueOf(userId), acceptedCount);
    }

    /** 멤버를 랭킹에서 제거한다 (ZREM). */
    public void remove(Long challengeId, Long userId) {
        redisTemplate.opsForZSet().remove(key(challengeId), String.valueOf(userId));
    }

    /** 인정 횟수 내림차순 상위 {@code limit}명을 점수와 함께 조회한다 (ZREVRANGE WITHSCORES). */
    public Set<TypedTuple<String>> findTopWithScores(Long challengeId, int limit) {
        return redisTemplate.opsForZSet()
                .reverseRangeWithScores(key(challengeId), 0, limit - 1L);
    }

    /**
     * 점수 구간에 속한 멤버를 조회한다. 동점자 보조 정렬은 DB fallback과 맞추기 위해
     * application layer에서 {@code acceptedCount DESC, lastCompletedAt ASC, userId ASC}로 수행한다.
     */
    public Set<TypedTuple<String>> findByScoreGreaterThanOrEqualWithScores(Long challengeId, double min) {
        return redisTemplate.opsForZSet().rangeByScoreWithScores(key(challengeId), min, Double.POSITIVE_INFINITY);
    }

    /** 해당 사용자의 점수 (ZSCORE). 미등록 시 {@code null}. */
    public Double findScore(Long challengeId, Long userId) {
        return redisTemplate.opsForZSet().score(key(challengeId), String.valueOf(userId));
    }

    /**
     * 공동 등수 계산용 — 특정 인정 횟수보다 높은 점수를 가진 멤버 수를 센다.
     * Redis ZCOUNT는 inclusive range라 다음 표현 가능한 double 값을 하한으로 사용해 동점을 제외한다.
     */
    public long countGreaterThanScore(Long challengeId, double acceptedCount) {
        Long count = redisTemplate.opsForZSet()
                .count(key(challengeId), Math.nextUp(acceptedCount), Double.POSITIVE_INFINITY);
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
