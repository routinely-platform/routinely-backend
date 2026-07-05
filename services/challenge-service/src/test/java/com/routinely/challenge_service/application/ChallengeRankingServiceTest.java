package com.routinely.challenge_service.application;

import com.routinely.challenge_service.application.dto.ChallengeRankingResult;
import com.routinely.challenge_service.domain.challenge.ChallengeRepository;
import com.routinely.challenge_service.domain.member.ChallengeMember;
import com.routinely.challenge_service.domain.member.ChallengeMemberRepository;
import com.routinely.challenge_service.domain.member.MembershipStatus;
import com.routinely.challenge_service.domain.summary.ChallengeMemberSummary;
import com.routinely.challenge_service.domain.summary.ChallengeMemberSummaryRepository;
import com.routinely.challenge_service.infrastructure.redis.ChallengeRankingRedisRepository;
import com.routinely.core.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.data.redis.core.DefaultTypedTuple;
import org.springframework.data.redis.core.ZSetOperations.TypedTuple;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("ChallengeRankingService")
class ChallengeRankingServiceTest {

    private static final long CHALLENGE_ID = 7L;
    private static final long ME = 42L;

    private ChallengeRepository challengeRepository;
    private ChallengeMemberRepository challengeMemberRepository;
    private ChallengeMemberSummaryRepository summaryRepository;
    private ChallengeRankingRedisRepository rankingRedisRepository;
    private ChallengeRankingService service;

    @BeforeEach
    void setUp() {
        challengeRepository = mock(ChallengeRepository.class);
        challengeMemberRepository = mock(ChallengeMemberRepository.class);
        summaryRepository = mock(ChallengeMemberSummaryRepository.class);
        rankingRedisRepository = mock(ChallengeRankingRedisRepository.class);
        service = new ChallengeRankingService(
                challengeRepository, challengeMemberRepository, summaryRepository, rankingRedisRepository);

        when(challengeRepository.existsById(CHALLENGE_ID)).thenReturn(true);
        when(challengeMemberRepository.findByChallengeIdAndUserIdAndStatus(CHALLENGE_ID, ME, MembershipStatus.ACTIVE))
                .thenReturn(Optional.of(mock(ChallengeMember.class)));
    }

    @Test
    @DisplayName("ZSET 상위 목록을 공동 등수로 조립하고 본인 순위(isMe)를 함께 반환한다")
    void getRanking_assemblesFromZsetWithMyRank() {
        // 달성률 내림차순: user100(90) > me42(80) = user7(80)
        when(rankingRedisRepository.findTopWithScores(eq(CHALLENGE_ID), anyInt()))
                .thenReturn(orderedTuples());
        when(rankingRedisRepository.findByScoreGreaterThanOrEqualWithScores(eq(CHALLENGE_ID), anyDouble()))
                .thenReturn(orderedTuples());
        when(rankingRedisRepository.findScore(CHALLENGE_ID, ME)).thenReturn(80.0);
        when(rankingRedisRepository.countGreaterThanScore(CHALLENGE_ID, 80.0)).thenReturn(1L);
        when(rankingRedisRepository.countMembers(CHALLENGE_ID)).thenReturn(3L);

        ChallengeRankingResult result = service.getRanking(CHALLENGE_ID, ME, 100);

        assertThat(result.totalMembers()).isEqualTo(3L);
        assertThat(result.rankings()).extracting(ChallengeRankingResult.Entry::rank)
                .containsExactly(1, 2, 2);
        assertThat(result.rankings()).extracting(ChallengeRankingResult.Entry::userId)
                .containsExactly(100L, 7L, 42L);
        assertThat(result.rankings()).extracting(ChallengeRankingResult.Entry::isMe)
                .containsExactly(false, false, true);

        assertThat(result.myRanking()).isNotNull();
        assertThat(result.myRanking().rank()).isEqualTo(2);
        assertThat(result.myRanking().userId()).isEqualTo(ME);
        assertThat(result.myRanking().achievementRate()).isEqualTo(80.0);
        assertThat(result.myRanking().isMe()).isTrue();
    }

    @Test
    @DisplayName("Redis 동점 후보를 DB와 같은 userId 오름차순으로 정렬한 뒤 상위 N명만 반환한다")
    void getRanking_whenRedisTieAtLimit_sortsTieByUserIdAsc() {
        when(rankingRedisRepository.findTopWithScores(CHALLENGE_ID, 3))
                .thenReturn(redisTopThreeBeforeTieNormalization());
        when(rankingRedisRepository.findByScoreGreaterThanOrEqualWithScores(CHALLENGE_ID, 80.0))
                .thenReturn(redisCandidatesIncludingTieBoundary());
        when(rankingRedisRepository.findScore(CHALLENGE_ID, ME)).thenReturn(70.0);
        when(rankingRedisRepository.countGreaterThanScore(CHALLENGE_ID, 70.0)).thenReturn(4L);
        when(rankingRedisRepository.countMembers(CHALLENGE_ID)).thenReturn(5L);

        ChallengeRankingResult result = service.getRanking(CHALLENGE_ID, ME, 3);

        assertThat(result.rankings()).extracting(ChallengeRankingResult.Entry::userId)
                .containsExactly(100L, 2L, 4L);
        assertThat(result.rankings()).extracting(ChallengeRankingResult.Entry::rank)
                .containsExactly(1, 2, 2);
        assertThat(result.myRanking().rank()).isEqualTo(5);
    }


    @Test
    @DisplayName("본인이 상위 목록에 없고 Redis 점수도 없으면 summary로 본인 랭킹을 계산한다")
    void getRanking_whenMyRankAbsent_calculatesMyRankingFromSummary() {
        // 상위 목록(ZSET)에 본인(42)이 없고, Redis 개인 점수도 미집계(null)
        when(rankingRedisRepository.findTopWithScores(eq(CHALLENGE_ID), anyInt()))
                .thenReturn(tuplesWithoutMe());
        when(rankingRedisRepository.findByScoreGreaterThanOrEqualWithScores(eq(CHALLENGE_ID), anyDouble()))
                .thenReturn(tuplesWithoutMe());
        when(rankingRedisRepository.findScore(CHALLENGE_ID, ME)).thenReturn(null);
        when(summaryRepository.findByChallengeIdAndUserId(CHALLENGE_ID, ME))
                .thenReturn(Optional.of(summary(ME, "50.00")));
        when(summaryRepository.countByChallengeIdAndAchievementRateGreaterThan(
                CHALLENGE_ID, new BigDecimal("50.00")))
                .thenReturn(5L);
        when(rankingRedisRepository.countMembers(CHALLENGE_ID)).thenReturn(3L);

        ChallengeRankingResult result = service.getRanking(CHALLENGE_ID, ME, 100);

        assertThat(result.myRanking()).isNotNull();
        assertThat(result.myRanking().rank()).isEqualTo(6);
        assertThat(result.myRanking().userId()).isEqualTo(ME);
        assertThat(result.myRanking().achievementRate()).isEqualTo(50.0);
        assertThat(result.rankings()).hasSize(3);
        assertThat(result.rankings()).extracting(ChallengeRankingResult.Entry::isMe)
                .containsOnly(false);
    }

    @Test
    @DisplayName("ZSET이 비면 summary로 fallback하고, 본인 순위도 summary 기준으로 계산한다")
    void getRanking_whenZsetEmpty_fallsBackToSummary() {
        when(rankingRedisRepository.findTopWithScores(eq(CHALLENGE_ID), anyInt())).thenReturn(Set.of());
        when(summaryRepository.findByChallengeIdOrderByAchievementRateDescUserIdAsc(eq(CHALLENGE_ID), any(Pageable.class)))
                .thenReturn(List.of(summary(100L, "90.00"), summary(ME, "80.00")));
        // ZSET이 비면 개인 점수 조회도 미스(null) — 실제 Redis는 미등록 멤버에 null을 반환한다.
        when(rankingRedisRepository.findScore(CHALLENGE_ID, ME)).thenReturn(null);
        when(summaryRepository.findByChallengeIdAndUserId(CHALLENGE_ID, ME))
                .thenReturn(Optional.of(summary(ME, "80.00")));
        when(summaryRepository.countByChallengeIdAndAchievementRateGreaterThan(
                CHALLENGE_ID, new BigDecimal("80.00")))
                .thenReturn(1L);
        when(rankingRedisRepository.countMembers(CHALLENGE_ID)).thenReturn(0L);
        // 상위 목록엔 2명만 잡히지만(limit 이내), 실제 전체 참여자는 5명인 상황
        when(summaryRepository.countByChallengeId(CHALLENGE_ID)).thenReturn(5L);

        ChallengeRankingResult result = service.getRanking(CHALLENGE_ID, ME, 100);

        assertThat(result.rankings()).extracting(ChallengeRankingResult.Entry::userId)
                .containsExactly(100L, 42L);
        assertThat(result.rankings()).extracting(ChallengeRankingResult.Entry::isMe)
                .containsExactly(false, true);
        // Redis 미스 → summary에서 "나보다 높은 점수 수 + 1"로 본인 랭킹을 계산한다.
        assertThat(result.myRanking()).isNotNull();
        assertThat(result.myRanking().rank()).isEqualTo(2);
        assertThat(result.myRanking().userId()).isEqualTo(ME);
        assertThat(result.myRanking().achievementRate()).isEqualTo(80.0);
        assertThat(result.myRanking().isMe()).isTrue();
        // ZCARD 0 → summary COUNT(5)로 fallback. 상위 목록 크기(2)로 축소되면 안 된다.
        assertThat(result.totalMembers()).isEqualTo(5L);
    }

    @Test
    @DisplayName("본인 summary가 아직 없어도 활성 멤버는 0점 기준으로 myRanking을 반환한다")
    void getRanking_whenMySummaryAbsent_returnsZeroScoreMyRanking() {
        when(rankingRedisRepository.findTopWithScores(eq(CHALLENGE_ID), anyInt()))
                .thenReturn(tuplesWithoutMe());
        when(rankingRedisRepository.findByScoreGreaterThanOrEqualWithScores(eq(CHALLENGE_ID), anyDouble()))
                .thenReturn(tuplesWithoutMe());
        when(rankingRedisRepository.findScore(CHALLENGE_ID, ME)).thenReturn(null);
        when(summaryRepository.findByChallengeIdAndUserId(CHALLENGE_ID, ME)).thenReturn(Optional.empty());
        when(summaryRepository.countByChallengeIdAndAchievementRateGreaterThan(CHALLENGE_ID, BigDecimal.ZERO))
                .thenReturn(3L);
        when(rankingRedisRepository.countMembers(CHALLENGE_ID)).thenReturn(3L);

        ChallengeRankingResult result = service.getRanking(CHALLENGE_ID, ME, 100);

        assertThat(result.myRanking()).isNotNull();
        assertThat(result.myRanking().rank()).isEqualTo(4);
        assertThat(result.myRanking().userId()).isEqualTo(ME);
        assertThat(result.myRanking().achievementRate()).isEqualTo(0.0);
        assertThat(result.myRanking().isMe()).isTrue();
    }

    @Test
    @DisplayName("챌린지 멤버가 아니면 NOT_CHALLENGE_MEMBER 예외를 던진다")
    void getRanking_whenNotMember_throws() {
        when(challengeMemberRepository.findByChallengeIdAndUserIdAndStatus(CHALLENGE_ID, ME, MembershipStatus.ACTIVE))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getRanking(CHALLENGE_ID, ME, 100))
                .isInstanceOf(BusinessException.class);
    }

    private Set<TypedTuple<String>> orderedTuples() {
        Set<TypedTuple<String>> tuples = new LinkedHashSet<>();
        tuples.add(new DefaultTypedTuple<>("100", 90.0));
        tuples.add(new DefaultTypedTuple<>("42", 80.0));
        tuples.add(new DefaultTypedTuple<>("7", 80.0));
        return tuples;
    }

    /** 본인(42)이 포함되지 않은 상위 목록. */
    private Set<TypedTuple<String>> tuplesWithoutMe() {
        Set<TypedTuple<String>> tuples = new LinkedHashSet<>();
        tuples.add(new DefaultTypedTuple<>("100", 90.0));
        tuples.add(new DefaultTypedTuple<>("7", 70.0));
        tuples.add(new DefaultTypedTuple<>("5", 60.0));
        return tuples;
    }

    /** Redis raw top 3에는 동점자 user5가 먼저 들어왔지만, user2가 같은 점수로 존재하는 상황. */
    private Set<TypedTuple<String>> redisTopThreeBeforeTieNormalization() {
        Set<TypedTuple<String>> tuples = new LinkedHashSet<>();
        tuples.add(new DefaultTypedTuple<>("100", 100.0));
        tuples.add(new DefaultTypedTuple<>("5", 80.0));
        tuples.add(new DefaultTypedTuple<>("4", 80.0));
        return tuples;
    }

    /** cutoff(80점) 이상 전체 후보. 최종 정렬 후 상위 3명은 100, 2, 4가 되어야 한다. */
    private Set<TypedTuple<String>> redisCandidatesIncludingTieBoundary() {
        Set<TypedTuple<String>> tuples = new LinkedHashSet<>();
        tuples.add(new DefaultTypedTuple<>("5", 80.0));
        tuples.add(new DefaultTypedTuple<>("100", 100.0));
        tuples.add(new DefaultTypedTuple<>("4", 80.0));
        tuples.add(new DefaultTypedTuple<>("2", 80.0));
        return tuples;
    }

    private ChallengeMemberSummary summary(long userId, String rate) {
        ChallengeMemberSummary summary = ChallengeMemberSummary.create(CHALLENGE_ID, userId);
        summary.applyCompletion(12, 10, new BigDecimal(rate), LocalDateTime.now());
        return summary;
    }
}
