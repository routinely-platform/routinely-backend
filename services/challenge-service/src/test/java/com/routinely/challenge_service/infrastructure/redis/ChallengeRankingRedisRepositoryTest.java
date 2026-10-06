package com.routinely.challenge_service.infrastructure.redis;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

@DisplayName("인정 횟수 Redis 점수 범위")
class ChallengeRankingRedisRepositoryTest {
    @Test @DisplayName("100보다 큰 점수도 양의 무한대까지 조회하고 동점은 제외한다")
    @SuppressWarnings("unchecked")
    void ranking_aboveHundred_hasNoRateCeiling() {
        var redis = mock(StringRedisTemplate.class);
        var zset = (ZSetOperations<String, String>) mock(ZSetOperations.class);
        when(redis.opsForZSet()).thenReturn(zset);
        when(zset.count("ranking:7", Math.nextUp(150.0), Double.POSITIVE_INFINITY)).thenReturn(2L);
        var repository = new ChallengeRankingRedisRepository(redis);
        repository.findByScoreGreaterThanOrEqualWithScores(7L, 150);
        assertThat(repository.countGreaterThanScore(7L, 150)).isEqualTo(2);
        verify(zset).rangeByScoreWithScores("ranking:7", 150, Double.POSITIVE_INFINITY);
    }
}
