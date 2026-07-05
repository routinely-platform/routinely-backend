package com.routinely.challenge_service.application;

import com.routinely.challenge_service.application.dto.ChallengeRankingResult;
import com.routinely.challenge_service.domain.challenge.ChallengeRepository;
import com.routinely.challenge_service.domain.member.ChallengeMemberRepository;
import com.routinely.challenge_service.domain.member.MembershipStatus;
import com.routinely.challenge_service.domain.summary.ChallengeMemberSummary;
import com.routinely.challenge_service.domain.summary.ChallengeMemberSummaryRepository;
import com.routinely.challenge_service.infrastructure.redis.ChallengeRankingRedisRepository;
import com.routinely.core.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.redis.core.ZSetOperations.TypedTuple;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

import static com.routinely.core.exception.ErrorCode.CHALLENGE_NOT_FOUND;
import static com.routinely.core.exception.ErrorCode.NOT_CHALLENGE_MEMBER;

/**
 * 챌린지 랭킹 조회 서비스. (#48, ADR-0028)
 *
 * <p>달성률 내림차순 랭킹을 Redis ZSET에서 조회하고(O(log N)), Redis 미스/장애 시
 * {@code challenge_member_summary} 테이블로 fallback한다.
 *
 * <p>응답의 {@code rankings}는 상위 N명을 반환한다. 등수는 위치가 아니라 달성률 기준 공동 등수로 계산해,
 * 같은 달성률이면 같은 rank를 부여한다. {@code myRanking}은 상위 목록 포함 여부와 무관하게 반환하며,
 * Redis 미스/장애 시에도 {@code challenge_member_summary}로 fallback한다.
 */
@Slf4j
@Service
@Transactional(readOnly = true)
public class ChallengeRankingService {

    private final ChallengeRepository challengeRepository;
    private final ChallengeMemberRepository challengeMemberRepository;
    private final ChallengeMemberSummaryRepository summaryRepository;
    private final ChallengeRankingRedisRepository rankingRedisRepository;

    public ChallengeRankingService(ChallengeRepository challengeRepository,
                                   ChallengeMemberRepository challengeMemberRepository,
                                   ChallengeMemberSummaryRepository summaryRepository,
                                   ChallengeRankingRedisRepository rankingRedisRepository) {
        this.challengeRepository = challengeRepository;
        this.challengeMemberRepository = challengeMemberRepository;
        this.summaryRepository = summaryRepository;
        this.rankingRedisRepository = rankingRedisRepository;
    }

    public ChallengeRankingResult getRanking(Long challengeId, Long requestUserId, int limit) {
        validateChallengeExists(challengeId);
        validateActiveMember(challengeId, requestUserId);

        List<UserScore> ordered = fetchTopRanked(challengeId, limit);
        ChallengeRankingResult.Entry myRanking = resolveMyRanking(challengeId, requestUserId);
        long totalMembers = resolveTotalMembers(challengeId);

        return assembleResult(ordered, requestUserId, myRanking, totalMembers);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 데이터 조립
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * 조회/계산해 둔 재료들을 {@link ChallengeRankingResult}로 변환한다.
     *
     * <p>{@code ordered}(달성률 내림차순)를 상위 N명 Entry로 변환한다.
     * 같은 달성률은 같은 rank를 부여하고, 다음 점수의 rank는 실제 위치(index + 1)를 따른다.
     */
    private ChallengeRankingResult assembleResult(List<UserScore> ordered, Long requestUserId,
                                                  ChallengeRankingResult.Entry myRanking, long totalMembers) {
        List<ChallengeRankingResult.Entry> rankings = new ArrayList<>(ordered.size());
        double previousScore = Double.NaN;
        int currentRank = 0;

        for (int i = 0; i < ordered.size(); i++) {
            UserScore userScore = ordered.get(i);
            if (i == 0 || Double.compare(previousScore, userScore.achievementRate()) != 0) {
                currentRank = i + 1;
                previousScore = userScore.achievementRate();
            }
            boolean isMe = userScore.userId().equals(requestUserId);
            rankings.add(new ChallengeRankingResult.Entry(
                    currentRank, userScore.userId(), userScore.achievementRate(), isMe));
        }

        return new ChallengeRankingResult(rankings, myRanking, totalMembers);
    }

    /**
     * 본인 순위 카드를 구성한다. 공동 등수 기준으로 "나보다 높은 점수를 가진 멤버 수 + 1"을 rank로 사용한다.
     *
     * <p>Redis에서 내 점수와 higher-count를 얻으면 Redis 기준으로 계산하고, Redis 미스/장애 시에는
     * summary 테이블로 fallback한다. summary row가 아직 없으면 활성 멤버의 초기 상태로 보고 0%로 계산한다.
     */
    private ChallengeRankingResult.Entry resolveMyRanking(Long challengeId, Long requestUserId) {
        try {
            Double score = rankingRedisRepository.findScore(challengeId, requestUserId);
            if (score != null) {
                long higherScoreCount = rankingRedisRepository.countGreaterThanScore(challengeId, score);
                return new ChallengeRankingResult.Entry(
                        toRank(higherScoreCount), requestUserId, score, true);
            }
        } catch (DataAccessException e) {
            log.warn("[Ranking] 본인 순위 Redis 조회 실패 - challengeId: {}, userId: {}",
                    challengeId, requestUserId, e);
        }

        return resolveMyRankingFromSummary(challengeId, requestUserId);
    }

    private ChallengeRankingResult.Entry resolveMyRankingFromSummary(Long challengeId, Long requestUserId) {
        BigDecimal score = summaryRepository.findByChallengeIdAndUserId(challengeId, requestUserId)
                .map(ChallengeMemberSummary::getAchievementRate)
                .orElse(BigDecimal.ZERO);
        long higherScoreCount = summaryRepository
                .countByChallengeIdAndAchievementRateGreaterThan(challengeId, score);
        return new ChallengeRankingResult.Entry(
                toRank(higherScoreCount), requestUserId, score.doubleValue(), true);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 조회 재료 준비 (구현 완료)
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * 상위 limit명을 조회한다. ZSET 우선, 비었거나 장애면 summary 테이블 fallback.
     * Redis의 동점 정렬(member 문자열 정렬)에 의존하지 않도록 cutoff 점수 이상 후보를 다시 가져와
     * DB fallback과 같은 {@code achievementRate DESC, userId ASC} 기준으로 정렬한다.
     */
    private List<UserScore> fetchTopRanked(Long challengeId, int limit) {
        try {
            Set<TypedTuple<String>> tuples = rankingRedisRepository.findTopWithScores(challengeId, limit);
            if (tuples != null && !tuples.isEmpty()) {
                double cutoffScore = findCutoffScore(tuples);
                Set<TypedTuple<String>> candidates =
                        rankingRedisRepository.findByScoreGreaterThanOrEqualWithScores(challengeId, cutoffScore);
                return toSortedUserScores(candidates, limit);
            }
        } catch (DataAccessException e) {
            log.warn("[Ranking] ZSET 조회 실패 — summary fallback - challengeId: {}", challengeId, e);
        }
        return fetchTopRankedFromSummary(challengeId, limit);
    }

    private double findCutoffScore(Set<TypedTuple<String>> tuples) {
        double cutoffScore = Double.POSITIVE_INFINITY;
        for (TypedTuple<String> tuple : tuples) {
            cutoffScore = Math.min(cutoffScore, scoreOf(tuple.getScore()));
        }
        return cutoffScore;
    }

    private List<UserScore> toSortedUserScores(Set<TypedTuple<String>> tuples, int limit) {
        if (tuples == null || tuples.isEmpty()) {
            return List.of();
        }
        return tuples.stream()
                .map(tuple -> new UserScore(Long.valueOf(tuple.getValue()), scoreOf(tuple.getScore())))
                .sorted(Comparator
                        .comparingDouble(UserScore::achievementRate).reversed()
                        .thenComparing(UserScore::userId))
                .limit(limit)
                .toList();
    }

    private List<UserScore> fetchTopRankedFromSummary(Long challengeId, int limit) {
        List<ChallengeMemberSummary> summaries = summaryRepository
                .findByChallengeIdOrderByAchievementRateDescUserIdAsc(challengeId, PageRequest.of(0, limit));
        List<UserScore> result = new ArrayList<>(summaries.size());
        for (ChallengeMemberSummary summary : summaries) {
            result.add(new UserScore(summary.getUserId(), summary.getAchievementRate().doubleValue()));
        }
        return result;
    }

    private long resolveTotalMembers(Long challengeId) {
        try {
            long count = rankingRedisRepository.countMembers(challengeId);
            if (count > 0) {
                return count;
            }
        } catch (DataAccessException e) {
            log.warn("[Ranking] 전체 멤버 수 조회 실패 - challengeId: {}", challengeId, e);
        }
        // ZSET 미스/장애 시 summary COUNT로 fallback한다.
        // 상위 목록은 limit으로 잘려 있어 그 크기를 그대로 쓰면 전체 인원보다 작게 나올 수 있다.
        return summaryRepository.countByChallengeId(challengeId);
    }

    private double scoreOf(Double score) {
        return score != null ? score : 0.0;
    }

    private int toRank(long higherScoreCount) {
        return Math.toIntExact(higherScoreCount + 1);
    }

    private void validateChallengeExists(Long challengeId) {
        if (!challengeRepository.existsById(challengeId)) {
            throw new BusinessException(CHALLENGE_NOT_FOUND);
        }
    }

    private void validateActiveMember(Long challengeId, Long userId) {
        challengeMemberRepository
                .findByChallengeIdAndUserIdAndStatus(challengeId, userId, MembershipStatus.ACTIVE)
                .orElseThrow(() -> new BusinessException(NOT_CHALLENGE_MEMBER));
    }

    /** 랭킹 정렬용 중간 표현 — 사용자별 달성률. */
    private record UserScore(Long userId, double achievementRate) {}
}
