package com.routinely.challenge_service.infrastructure.persistence;

import com.querydsl.core.types.Order;
import com.querydsl.core.types.OrderSpecifier;
import com.querydsl.core.types.SubQueryExpression;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.JPAExpressions;
import com.querydsl.jpa.impl.JPAQueryFactory;
import com.routinely.challenge_service.domain.challenge.Challenge;
import com.routinely.challenge_service.domain.challenge.ChallengeLifecycleStatus;
import com.routinely.challenge_service.domain.challenge.ChallengeQueryRepository;
import com.routinely.challenge_service.domain.challenge.ChallengeSearchCondition;
import com.routinely.challenge_service.domain.challenge.ChallengeSort;
import com.routinely.challenge_service.domain.challenge.MyChallengeSearchCondition;
import com.routinely.challenge_service.domain.challenge.MyChallengeSort;
import com.routinely.challenge_service.domain.challenge.QChallenge;
import com.routinely.challenge_service.domain.member.ChallengeMember;
import com.routinely.challenge_service.domain.member.MembershipStatus;
import com.routinely.challenge_service.domain.member.QChallengeMember;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

import java.util.List;

@Repository
public class ChallengeQueryRepositoryImpl implements ChallengeQueryRepository {

    private static final QChallenge challenge = QChallenge.challenge;
    private static final QChallengeMember member = QChallengeMember.challengeMember;

    private final JPAQueryFactory queryFactory;

    public ChallengeQueryRepositoryImpl(JPAQueryFactory queryFactory) {
        this.queryFactory = queryFactory;
    }

    @Override
    public Page<Challenge> findPublicChallenges(Long userId, ChallengeSearchCondition cond, Pageable pageable) {
        List<Challenge> content = queryFactory
                .selectFrom(challenge)
                .where(
                        challenge.isPublic.isTrue(),
                        challenge.status.eq(ChallengeLifecycleStatus.WAITING),
                        notAlreadyJoinedOrExpelled(userId),
                        titleContains(cond.keyword()),
                        categoryCodeIn(cond.categoryCodes())
                )
                .orderBy(toPublicOrderSpecifiers(cond.sort()))
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch();

        Long total = queryFactory
                .select(challenge.count())
                .from(challenge)
                .where(
                        challenge.isPublic.isTrue(),
                        challenge.status.eq(ChallengeLifecycleStatus.WAITING),
                        notAlreadyJoinedOrExpelled(userId),
                        titleContains(cond.keyword()),
                        categoryCodeIn(cond.categoryCodes())
                )
                .fetchOne();

        return new PageImpl<>(content, pageable, total != null ? total : 0L);
    }

    @Override
    public Page<ChallengeMember> findMyJoinedChallenges(Long userId, MyChallengeSearchCondition cond, Pageable pageable) {
        BooleanExpression endImminentFilter = cond.sort() == MyChallengeSort.END_IMMINENT
                ? challenge.status.ne(ChallengeLifecycleStatus.ENDED)
                : null;

        List<ChallengeMember> content = queryFactory
                .selectFrom(member)
                .join(member.challenge, challenge).fetchJoin()
                .where(
                        member.userId.eq(userId),
                        member.status.eq(MembershipStatus.ACTIVE),
                        titleContains(cond.keyword()),
                        categoryCodeIn(cond.categoryCodes()),
                        challengeStatusEq(cond.status()),
                        endImminentFilter
                )
                .orderBy(toMyOrderSpecifiers(cond.sort()))
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch();

        Long total = queryFactory
                .select(member.count())
                .from(member)
                .join(member.challenge, challenge)
                .where(
                        member.userId.eq(userId),
                        member.status.eq(MembershipStatus.ACTIVE),
                        titleContains(cond.keyword()),
                        categoryCodeIn(cond.categoryCodes()),
                        challengeStatusEq(cond.status()),
                        endImminentFilter
                )
                .fetchOne();

        return new PageImpl<>(content, pageable, total != null ? total : 0L);
    }

    // 이미 ACTIVE 또는 EXPELLED 상태인 userId를 제외하는 NOT EXISTS 서브쿼리
    private BooleanExpression notAlreadyJoinedOrExpelled(Long userId) {
        QChallengeMember cm = new QChallengeMember("cm");
        return JPAExpressions
                .selectOne()
                .from(cm)
                .where(
                        cm.challenge.eq(challenge),
                        cm.userId.eq(userId),
                        cm.status.in(MembershipStatus.ACTIVE, MembershipStatus.EXPELLED)
                )
                .notExists();
    }

    private BooleanExpression titleContains(String keyword) {
        return StringUtils.hasText(keyword) ? challenge.title.containsIgnoreCase(keyword) : null;
    }

    private BooleanExpression categoryCodeIn(List<String> categoryCodes) {
        return (categoryCodes != null && !categoryCodes.isEmpty())
                ? challenge.categoryCode.in(categoryCodes)
                : null;
    }

    private BooleanExpression challengeStatusEq(ChallengeLifecycleStatus status) {
        return status != null ? challenge.status.eq(status) : null;
    }

    private OrderSpecifier<?>[] toPublicOrderSpecifiers(ChallengeSort sort) {
        return switch (sort) {
            case IMMINENT -> new OrderSpecifier<?>[] {challenge.startedAt.asc(), challenge.id.asc()};
            case POPULAR -> {
                // ACTIVE 멤버 수 기준 내림차순 — 상관 서브쿼리로 구현
                // LEFT JOIN + groupBy 방식은 Hibernate 6 페이징 시 count 쿼리가 복잡해지므로 서브쿼리 방식 선택
                QChallengeMember cm = new QChallengeMember("cm_popular");
                SubQueryExpression<Long> memberCount = JPAExpressions
                        .select(cm.count())
                        .from(cm)
                        .where(cm.challenge.eq(challenge), cm.status.eq(MembershipStatus.ACTIVE));
                yield new OrderSpecifier<?>[] {new OrderSpecifier<>(Order.DESC, memberCount), challenge.id.asc()};
            }
            default -> new OrderSpecifier<?>[] {challenge.createdAt.desc(), challenge.id.desc()}; // LATEST
        };
    }

    private OrderSpecifier<?>[] toMyOrderSpecifiers(MyChallengeSort sort) {
        return switch (sort) {
            case END_IMMINENT -> new OrderSpecifier<?>[] {challenge.endedAt.asc(), challenge.id.asc()};
            default -> new OrderSpecifier<?>[] {member.joinedAt.desc(), member.id.desc()}; // RECENT_JOIN
        };
    }
}
