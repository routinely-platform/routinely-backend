package com.routinely.challenge_service.infrastructure.scheduler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.routinely.challenge_service.application.ChallengeService;
import com.routinely.challenge_service.domain.challenge.Challenge;
import com.routinely.challenge_service.domain.challenge.ChallengeLifecycleStatus;
import com.routinely.challenge_service.domain.challenge.ChallengeQueryRepository;
import com.routinely.challenge_service.domain.challenge.ChallengeRepository;
import com.routinely.challenge_service.domain.challenge.ChallengeRoutineDefinition;
import com.routinely.challenge_service.domain.member.ChallengeMember;
import com.routinely.challenge_service.domain.member.ChallengeMemberRepository;
import com.routinely.challenge_service.domain.outbox.ChallengeOutbox;
import com.routinely.challenge_service.domain.outbox.ChallengeOutboxRepository;
import com.routinely.challenge_service.infrastructure.kafka.KafkaTopic;
import com.routinely.core.schedule.ScheduleType;
import com.routinely.jpa.config.JpaAuditingConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 시작 전이와 참여가 겹쳐도 {@code challenge.started}의 {@code members}에서 참여자가 빠지지 않는지 고정한다.
 *
 * <p>Mock으로는 행 락을 검증할 수 없어 실제 PostgreSQL에서 트랜잭션 두 개를 겹친다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaAuditingConfig.class, ChallengeStatusTransitionScheduler.class, ChallengeService.class,
        ChallengeStartConcurrencyTest.Config.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:tc:postgresql:17-alpine:///challenge_start_db",
        "spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver",
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect"
})
@DisplayName("챌린지 시작 전이 동시성")
class ChallengeStartConcurrencyTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 6, 8);
    private static final long LEADER_ID = 1L;
    private static final long JOINER_ID = 2L;

    @TestConfiguration
    static class Config {
        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-06-07T15:00:00Z"), ZoneId.of("Asia/Seoul"));
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }

    @MockitoBean ChallengeQueryRepository challengeQueryRepository;

    @Autowired ChallengeRepository challenges;
    @Autowired ChallengeMemberRepository members;
    @Autowired ChallengeOutboxRepository outboxes;
    @Autowired ChallengeStatusTransitionScheduler scheduler;
    @Autowired ChallengeService challengeService;
    @Autowired ObjectMapper objectMapper;
    @Autowired PlatformTransactionManager transactionManager;

    @Test
    @DisplayName("참여가 커밋되기 전에 시작 전이가 돌면 기다렸다가 그 참여자를 members에 싣는다")
    void startTransition_whileJoinUncommitted_includesJoiner() throws Exception {
        Challenge challenge = challenges.saveAndFlush(Challenge.builder()
                .routineDefinition(ChallengeRoutineDefinition.of("루틴", ScheduleType.DAILY, null, null))
                .creatorUserId(LEADER_ID).title("걷기").maxMembers(10).categoryCode("HEALTH")
                .startedAt(TODAY).endedAt(TODAY.plusDays(29)).build());
        members.saveAndFlush(ChallengeMember.createLeader(challenge, LEADER_ID, TODAY.minusDays(1).atStartOfDay()));
        Long challengeId = challenge.getId();

        CompletableFuture<Void> transition = new TransactionTemplate(transactionManager).execute(status -> {
            challengeService.joinChallenge(challengeId, JOINER_ID);
            CompletableFuture<Void> running = CompletableFuture.runAsync(scheduler::transitionChallengeStatus);
            // 스케줄러가 멤버를 먼저 읽어 버리면 이 참여자가 members에서 빠진다 — 대상 조회에서 기다려야 한다.
            assertThatThrownBy(() -> running.get(1, TimeUnit.SECONDS)).isInstanceOf(TimeoutException.class);
            return running;
        });
        transition.get(10, TimeUnit.SECONDS);

        ChallengeOutbox started = outboxes.findAll().stream()
                .filter(outbox -> outbox.getAggregateId().equals(challengeId))
                .filter(outbox -> outbox.getEventType().equals(KafkaTopic.CHALLENGE_STARTED))
                .findFirst().orElseThrow();
        JsonNode payload = objectMapper.readTree(started.getPayload());
        assertThat(payload.get("members")).extracting(member -> member.get("userId").asLong())
                .containsExactly(LEADER_ID, JOINER_ID);
        assertThat(challenges.findById(challengeId).orElseThrow().getStatus())
                .isEqualTo(ChallengeLifecycleStatus.ACTIVE);
    }
}
