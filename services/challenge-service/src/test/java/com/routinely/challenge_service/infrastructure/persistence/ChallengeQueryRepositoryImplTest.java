package com.routinely.challenge_service.infrastructure.persistence;

import com.routinely.challenge_service.domain.challenge.Challenge;
import com.routinely.challenge_service.domain.challenge.ChallengeLifecycleStatus;
import com.routinely.challenge_service.domain.challenge.ChallengeSearchCondition;
import com.routinely.challenge_service.domain.challenge.ChallengeSort;
import com.routinely.challenge_service.domain.challenge.MyChallengeSearchCondition;
import com.routinely.challenge_service.domain.challenge.MyChallengeSort;
import com.routinely.challenge_service.domain.member.ChallengeMember;
import com.routinely.challenge_service.domain.member.ChallengeMemberRole;
import com.routinely.challenge_service.domain.member.MembershipStatus;
import com.routinely.challenge_service.infrastructure.config.QueryDslConfig;
import com.routinely.jpa.config.JpaAuditingConfig;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase
@Import({JpaAuditingConfig.class, QueryDslConfig.class, ChallengeQueryRepositoryImpl.class})
@TestPropertySource(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"
})
@DisplayName("ChallengeQueryRepositoryImpl")
class ChallengeQueryRepositoryImplTest {

    @Autowired
    EntityManager entityManager;

    @Autowired
    ChallengeQueryRepositoryImpl repository;

    @Test
    @DisplayName("공개챌린지목록조회_keyword_categoryCode_POPULAR_조건을_적용한다")
    void findPublicChallenges_appliesKeywordCategoryAndPopularSort() {
        Challenge popular = persistChallenge("아침 운동", true, ChallengeLifecycleStatus.WAITING, "EXERCISE",
                LocalDate.of(2026, 6, 10), LocalDate.of(2026, 6, 30));
        Challenge lessPopular = persistChallenge("새벽 운동", true, ChallengeLifecycleStatus.WAITING, "EXERCISE",
                LocalDate.of(2026, 6, 11), LocalDate.of(2026, 6, 30));
        Challenge study = persistChallenge("아침 독서", true, ChallengeLifecycleStatus.WAITING, "STUDY",
                LocalDate.of(2026, 6, 12), LocalDate.of(2026, 6, 30));
        Challenge alreadyJoined = persistChallenge("저녁 운동", true, ChallengeLifecycleStatus.WAITING, "EXERCISE",
                LocalDate.of(2026, 6, 13), LocalDate.of(2026, 6, 30));
        Challenge expelled = persistChallenge("운동 강퇴", true, ChallengeLifecycleStatus.WAITING, "EXERCISE",
                LocalDate.of(2026, 6, 14), LocalDate.of(2026, 6, 30));
        persistChallenge("운동 진행중", true, ChallengeLifecycleStatus.ACTIVE, "EXERCISE",
                LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30));
        persistChallenge("운동 비공개", false, ChallengeLifecycleStatus.WAITING, "EXERCISE",
                LocalDate.of(2026, 6, 15), LocalDate.of(2026, 6, 30));

        persistMember(popular, 201L, MembershipStatus.ACTIVE, LocalDateTime.of(2026, 6, 1, 9, 0));
        persistMember(popular, 202L, MembershipStatus.ACTIVE, LocalDateTime.of(2026, 6, 1, 10, 0));
        persistMember(lessPopular, 203L, MembershipStatus.ACTIVE, LocalDateTime.of(2026, 6, 1, 11, 0));
        persistMember(study, 204L, MembershipStatus.ACTIVE, LocalDateTime.of(2026, 6, 1, 12, 0));
        persistMember(alreadyJoined, 100L, MembershipStatus.ACTIVE, LocalDateTime.of(2026, 6, 1, 13, 0));
        persistMember(expelled, 100L, MembershipStatus.EXPELLED, LocalDateTime.of(2026, 6, 1, 14, 0));
        flushAndClear();

        ChallengeSearchCondition cond = new ChallengeSearchCondition(
                " 운동 ",
                List.of("", "EXERCISE", "EXERCISE", " "),
                ChallengeSort.POPULAR
        );

        Page<Challenge> result = repository.findPublicChallenges(100L, cond, PageRequest.of(0, 10));

        assertThat(result.getTotalElements()).isEqualTo(2);
        assertThat(result.getContent())
                .extracting(Challenge::getTitle)
                .containsExactly("아침 운동", "새벽 운동");
    }

    @Test
    @DisplayName("내참여챌린지목록조회_END_IMMINENT는_ENDED를_제외하고_종료일순으로_정렬한다")
    void findMyJoinedChallenges_endImminent_excludesEndedAndSortsByEndedAt() {
        Challenge waiting = persistChallenge("대기 챌린지", true, ChallengeLifecycleStatus.WAITING, "EXERCISE",
                LocalDate.of(2026, 6, 10), LocalDate.of(2026, 6, 30));
        Challenge active = persistChallenge("진행 챌린지", true, ChallengeLifecycleStatus.ACTIVE, "EXERCISE",
                LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 20));
        Challenge ended = persistChallenge("종료 챌린지", true, ChallengeLifecycleStatus.ENDED, "EXERCISE",
                LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 31));
        persistMember(waiting, 100L, MembershipStatus.ACTIVE, LocalDateTime.of(2026, 6, 1, 9, 0));
        persistMember(active, 100L, MembershipStatus.ACTIVE, LocalDateTime.of(2026, 6, 2, 9, 0));
        persistMember(ended, 100L, MembershipStatus.ACTIVE, LocalDateTime.of(2026, 6, 3, 9, 0));
        flushAndClear();

        MyChallengeSearchCondition cond = new MyChallengeSearchCondition(
                null,
                null,
                null,
                MyChallengeSort.END_IMMINENT
        );

        Page<ChallengeMember> result = repository.findMyJoinedChallenges(100L, cond, PageRequest.of(0, 10));

        assertThat(result.getTotalElements()).isEqualTo(2);
        assertThat(result.getContent())
                .extracting(member -> member.getChallenge().getTitle())
                .containsExactly("진행 챌린지", "대기 챌린지");
    }

    private Challenge persistChallenge(
            String title,
            boolean isPublic,
            ChallengeLifecycleStatus status,
            String categoryCode,
            LocalDate startedAt,
            LocalDate endedAt
    ) {
        Challenge challenge = Challenge.builder()
                .creatorUserId(1L)
                .title(title)
                .description(title + " 설명")
                .isPublic(isPublic)
                .inviteCode(isPublic ? null : title + "-invite")
                .maxMembers(10)
                .status(status)
                .categoryCode(categoryCode)
                .startedAt(startedAt)
                .endedAt(endedAt)
                .build();
        entityManager.persist(challenge);
        return challenge;
    }

    private ChallengeMember persistMember(
            Challenge challenge,
            Long userId,
            MembershipStatus status,
            LocalDateTime joinedAt
    ) {
        ChallengeMember member = ChallengeMember.builder()
                .challenge(challenge)
                .userId(userId)
                .role(ChallengeMemberRole.MEMBER)
                .status(status)
                .joinedAt(joinedAt)
                .leftAt(status == MembershipStatus.ACTIVE ? null : joinedAt.plusDays(1))
                .build();
        entityManager.persist(member);
        return member;
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }
}
