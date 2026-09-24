package com.routinely.challenge_service.infrastructure.persistence;

import com.routinely.challenge_service.domain.challenge.Challenge;
import com.routinely.challenge_service.domain.challenge.ChallengeRepository;
import com.routinely.challenge_service.domain.member.ChallengeMember;
import com.routinely.challenge_service.domain.member.ChallengeMemberRepository;
import com.routinely.challenge_service.domain.summary.ChallengeMemberSummary;
import com.routinely.challenge_service.domain.summary.ChallengeMemberSummaryRepository;
import com.routinely.jpa.config.JpaAuditingConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.data.domain.PageRequest;
import java.time.*;
import static org.assertj.core.api.Assertions.*;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaAuditingConfig.class)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:tc:postgresql:17-alpine:///ranking_db",
        "spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver",
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect"
})
@DisplayName("PostgreSQL 인정 횟수 랭킹")
class ChallengeRankingPersistenceTest {
    @Autowired ChallengeRepository challenges;
    @Autowired ChallengeMemberRepository members;
    @Autowired ChallengeMemberSummaryRepository summaries;

    @Test @DisplayName("V5 스키마는 100회 초과 저장을 허용하고 탈퇴 멤버 제외 및 동점 시간 정렬을 적용한다")
    void ranking_migrationAndFallback_matchContract() {
        var challenge = challenges.saveAndFlush(Challenge.builder().creatorUserId(1L).title("걷기")
                .maxMembers(10).categoryCode("HEALTH")
                .startedAt(LocalDate.of(2026, 9, 1)).endedAt(LocalDate.of(2026, 9, 30)).build());
        var time = LocalDateTime.of(2026, 9, 1, 0, 0);
        for (long user = 1; user <= 4; user++) {
            var member = ChallengeMember.createMember(challenge, user, time);
            if (user == 3) member.leave(time.plusDays(1));
            members.saveAndFlush(member);
            var summary = ChallengeMemberSummary.create(challenge.getId(), user);
            summary.applyAcceptedCount(user == 3 ? 200 : 150, user,
                    user == 4 ? null : time.plusDays(3 - user));
            summaries.saveAndFlush(summary);
        }
        assertThat(members.findLockedByChallengeIdAndUserId(challenge.getId(), 1L)).isPresent();
        assertThat(summaries.findActiveRanking(challenge.getId(), PageRequest.of(0, 20)))
                .extracting(ChallengeMemberSummary::getUserId).containsExactly(2L, 1L, 4L);
        assertThat(summaries.countActiveWithHigherScore(challenge.getId(), 150)).isZero();
        assertThat(summaries.countActiveWithHigherScore(challenge.getId(), 149)).isEqualTo(3);
    }
}
