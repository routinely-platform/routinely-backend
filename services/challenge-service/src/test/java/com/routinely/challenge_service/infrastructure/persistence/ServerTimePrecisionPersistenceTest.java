package com.routinely.challenge_service.infrastructure.persistence;

import com.routinely.challenge_service.domain.challenge.Challenge;
import com.routinely.challenge_service.domain.challenge.ChallengeRepository;
import com.routinely.challenge_service.domain.challenge.ChallengeRoutineDefinition;
import com.routinely.challenge_service.domain.inbox.ChallengeInbox;
import com.routinely.challenge_service.domain.inbox.ChallengeInboxRepository;
import com.routinely.challenge_service.domain.member.ChallengeMember;
import com.routinely.challenge_service.domain.member.ChallengeMemberRepository;
import com.routinely.challenge_service.domain.outbox.ChallengeOutbox;
import com.routinely.challenge_service.domain.outbox.ChallengeOutboxRepository;
import com.routinely.challenge_service.domain.summary.ChallengeMemberSummary;
import com.routinely.challenge_service.domain.summary.ChallengeMemberSummaryRepository;
import com.routinely.core.schedule.ScheduleType;
import com.routinely.core.time.RoutinelyClocks;
import com.routinely.jpa.config.JpaAuditingConfig;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 서버가 만든 시각이 저장 직후 메모리 값과 DB 재조회 값에서 같은지 본다(#213).
 *
 * <p>리눅스 시계처럼 나노초 9자리가 있는 원천({@code …740903532})을 <b>운영과 같은 팩토리</b>
 * {@link RoutinelyClocks#micros(Clock)}에 넣는다. {@code Clock.fixed}를 그대로 주입하면 마이크로초 절단을 건너뛰어
 * macOS 로컬처럼 문제가 가려진다. 기대값은 {@code .740903}으로 직접 쓴다 — 저장값 = 재조회값만 보면 밀리초까지
 * 버린 구현도 통과한다.
 *
 * <p>DB별 하위 클래스가 H2와 PostgreSQL(Testcontainers)에서 같은 시나리오를 돌린다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaAuditingConfig.class, ServerTimePrecisionPersistenceTest.ClockConfig.class})
abstract class ServerTimePrecisionPersistenceTest {

    static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    /** 나노초 원천 — 2026-11-13 14:58:44.740903532 (서울) */
    static final Instant NANO_INSTANT = Instant.parse("2026-11-13T05:58:44.740903532Z");
    /** 서비스 Clock(서울)으로 만든 시각의 기대값 */
    static final LocalDateTime SEOUL_MICROS = LocalDateTime.parse("2026-11-13T14:58:44.740903");
    /** Auditing(JVM 기본 시간대)으로 만든 시각의 기대값 */
    static final LocalDateTime AUDITING_MICROS =
            LocalDateTime.ofInstant(Instant.parse("2026-11-13T05:58:44.740903Z"), ZoneId.systemDefault());

    @TestConfiguration
    static class ClockConfig {
        @Bean
        Clock clock() {
            return RoutinelyClocks.micros(Clock.fixed(NANO_INSTANT, SEOUL));
        }
    }

    @Autowired Clock clock;
    @Autowired EntityManager entityManager;
    @Autowired ChallengeRepository challenges;
    @Autowired ChallengeMemberRepository members;
    @Autowired ChallengeMemberSummaryRepository summaries;
    @Autowired ChallengeOutboxRepository outboxes;
    @Autowired ChallengeInboxRepository inboxes;

    @Test
    @DisplayName("Auditing — 챌린지 생성 응답의 createdAt이 DB 재조회 값과 같다")
    void challengeAuditing_inMemoryEqualsReloaded() {
        Challenge saved = challenges.saveAndFlush(challenge());
        entityManager.clear();

        Challenge reloaded = challenges.findById(saved.getId()).orElseThrow();

        assertThat(saved.getCreatedAt()).isEqualTo(AUDITING_MICROS);
        assertThat(reloaded.getCreatedAt()).isEqualTo(saved.getCreatedAt());
        assertThat(reloaded.getUpdatedAt()).isEqualTo(saved.getUpdatedAt());
    }

    @Test
    @DisplayName("Clock 직접 생성 — 참여 응답의 joinedAt이 DB 재조회 값과 같다")
    void memberJoinedAt_inMemoryEqualsReloaded() {
        Challenge challenge = challenges.saveAndFlush(challenge());
        ChallengeMember saved = members.saveAndFlush(
                ChallengeMember.createMember(challenge, 2L, LocalDateTime.now(clock)));
        entityManager.clear();

        ChallengeMember reloaded = members.findById(saved.getId()).orElseThrow();

        assertThat(saved.getJoinedAt()).isEqualTo(SEOUL_MICROS);
        assertThat(reloaded.getJoinedAt()).isEqualTo(saved.getJoinedAt());
    }

    @Test
    @DisplayName("Outbox created_at · Inbox received_at — 호출부 Clock 값이 DB 재조회 값과 같다")
    void outboxAndInbox_inMemoryEqualsReloaded() {
        ChallengeOutbox outbox = outboxes.saveAndFlush(ChallengeOutbox.create(
                "CHALLENGE", 1L, "challenge.member.joined", "{}", "precision-outbox", LocalDateTime.now(clock)));
        ChallengeInbox inbox = inboxes.saveAndFlush(ChallengeInbox.received(
                "precision-inbox", "routine.execution.completed", "{}", "CHALLENGE", 1L, LocalDateTime.now(clock)));
        entityManager.clear();

        assertThat(outbox.getCreatedAt()).isEqualTo(SEOUL_MICROS);
        assertThat(outboxes.findById(outbox.getId()).orElseThrow().getCreatedAt()).isEqualTo(outbox.getCreatedAt());
        assertThat(inbox.getReceivedAt()).isEqualTo(SEOUL_MICROS);
        assertThat(inboxes.findById(inbox.getId()).orElseThrow().getReceivedAt()).isEqualTo(inbox.getReceivedAt());
    }

    @Test
    @DisplayName("랭킹 집계(BaseEntity로 전환) — created_at · updated_at이 Auditing으로 채워지고 DB 값과 같다")
    void summaryAuditing_inMemoryEqualsReloaded() {
        Challenge challenge = challenges.saveAndFlush(challenge());
        ChallengeMemberSummary saved = summaries.saveAndFlush(ChallengeMemberSummary.create(challenge.getId(), 3L));
        entityManager.clear();

        ChallengeMemberSummary reloaded = summaries.findById(saved.getId()).orElseThrow();

        assertThat(saved.getCreatedAt()).isEqualTo(AUDITING_MICROS);
        assertThat(saved.getUpdatedAt()).isEqualTo(AUDITING_MICROS);
        assertThat(reloaded.getCreatedAt()).isEqualTo(saved.getCreatedAt());
        assertThat(reloaded.getUpdatedAt()).isEqualTo(saved.getUpdatedAt());
    }

    private static Challenge challenge() {
        return Challenge.builder()
                .routineDefinition(ChallengeRoutineDefinition.of("루틴", ScheduleType.DAILY, null, null))
                .creatorUserId(1L).title("걷기").maxMembers(10).categoryCode("HEALTH")
                .startedAt(LocalDate.of(2026, 12, 1)).endedAt(LocalDate.of(2026, 12, 31)).build();
    }
}
