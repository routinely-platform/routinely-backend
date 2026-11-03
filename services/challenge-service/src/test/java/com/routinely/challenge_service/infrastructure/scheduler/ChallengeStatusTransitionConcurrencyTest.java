package com.routinely.challenge_service.infrastructure.scheduler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.routinely.challenge_service.application.ChallengeImageWriter;
import com.routinely.challenge_service.application.ChallengeService;
import com.routinely.challenge_service.domain.challenge.Challenge;
import com.routinely.challenge_service.domain.challenge.ChallengeImage;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * 상태 전이와 요청이 같은 챌린지 행을 두고 겹칠 때 서로의 변경을 잃지 않는지 고정한다.
 *
 * <p>Mock으로는 행 락을 검증할 수 없어 실제 PostgreSQL에서 트랜잭션 두 개를 겹친다.
 * 먼저 시작한 트랜잭션이 행을 잡은 채로 스케줄러를 돌리고, 스케줄러가 <b>실제로 락을 기다리는 것</b>을
 * {@code pg_stat_activity}로 확인한 뒤 커밋한다 — 시간으로 기다리면 스레드 시작 지연을 락 대기로 오인한다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaAuditingConfig.class, ChallengeStatusTransitionScheduler.class, ChallengeService.class,
        ChallengeImageWriter.class, ChallengeStatusTransitionConcurrencyTest.Config.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:tc:postgresql:17-alpine:///challenge_transition_db",
        "spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver",
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect"
})
@DisplayName("챌린지 상태 전이 동시성")
class ChallengeStatusTransitionConcurrencyTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 6, 8);
    private static final long LEADER_ID = 1L;
    private static final long JOINER_ID = 2L;
    private static final String NEW_KEY = "challenge-images/2026/06/new.jpg";

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
    @Autowired ChallengeImageWriter challengeImageWriter;
    @Autowired ObjectMapper objectMapper;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired DataSource dataSource;

    @Test
    @DisplayName("참여가 커밋되기 전에 시작 전이가 돌면 기다렸다가 그 참여자를 members에 싣는다")
    void startTransition_whileJoinUncommitted_includesJoiner() throws Exception {
        Long challengeId = saveChallengeWithLeader(TODAY, TODAY.plusDays(29), false);

        CompletableFuture<Void> transition = new TransactionTemplate(transactionManager).execute(status -> {
            challengeService.joinChallenge(challengeId, JOINER_ID);
            // 스케줄러가 멤버를 먼저 읽어 버리면 이 참여자가 members에서 빠진다 — 대상 조회에서 기다려야 한다.
            return runSchedulerAndAwaitLockWait();
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

    @Test
    @DisplayName("이미지 변경이 커밋되기 전에 시작 전이가 돌면 기다렸다가 시작하고, 둘 다 남는다")
    void startTransition_whileImageChangeUncommitted_keepsBoth() throws Exception {
        Long challengeId = saveChallengeWithLeader(TODAY, TODAY.plusDays(29), false);

        CompletableFuture<Void> transition = new TransactionTemplate(transactionManager).execute(status -> {
            challengeImageWriter.changeImage(challengeId, LEADER_ID, ChallengeImage.of("new-url", NEW_KEY));
            // 이미지 변경이 일반 조회로 읽으면 커밋 때 status가 WAITING으로 되돌아간다.
            return runSchedulerAndAwaitLockWait();
        });
        transition.get(10, TimeUnit.SECONDS);

        Challenge challenge = challenges.findById(challengeId).orElseThrow();
        assertThat(challenge.getStatus()).isEqualTo(ChallengeLifecycleStatus.ACTIVE);
        assertThat(challenge.getImageObjectKey()).isEqualTo(NEW_KEY);
    }

    @Test
    @DisplayName("이미지 변경이 커밋되기 전에 종료 전이가 돌면 기다렸다가 종료하고, 새 이미지가 남는다")
    void endTransition_whileImageChangeUncommitted_keepsNewImage() throws Exception {
        Long challengeId = saveChallengeWithLeader(TODAY.minusDays(30), TODAY.minusDays(1), true);

        CompletableFuture<Void> transition = new TransactionTemplate(transactionManager).execute(status -> {
            challengeImageWriter.changeImage(challengeId, LEADER_ID, ChallengeImage.of("new-url", NEW_KEY));
            // 종료 전이가 일반 조회로 읽으면 커밋 때 이미지가 이전 값으로 되돌아간다.
            return runSchedulerAndAwaitLockWait();
        });
        transition.get(10, TimeUnit.SECONDS);

        Challenge challenge = challenges.findById(challengeId).orElseThrow();
        assertThat(challenge.getStatus()).isEqualTo(ChallengeLifecycleStatus.ENDED);
        assertThat(challenge.getImageObjectKey()).isEqualTo(NEW_KEY);
    }

    private Long saveChallengeWithLeader(LocalDate startedAt, LocalDate endedAt, boolean active) {
        Challenge challenge = Challenge.builder()
                .routineDefinition(ChallengeRoutineDefinition.of("루틴", ScheduleType.DAILY, null, null))
                .creatorUserId(LEADER_ID).title("걷기").maxMembers(10).categoryCode("HEALTH")
                .startedAt(startedAt).endedAt(endedAt).build();
        if (active) {
            challenge.activate();
        }
        challenges.saveAndFlush(challenge);
        members.saveAndFlush(ChallengeMember.createLeader(challenge, LEADER_ID, startedAt.minusDays(1).atStartOfDay()));
        return challenge.getId();
    }

    /** 스케줄러를 다른 스레드에서 돌리고, 그 트랜잭션이 행 락을 기다리기 시작할 때까지 막는다. */
    private CompletableFuture<Void> runSchedulerAndAwaitLockWait() {
        CompletableFuture<Void> running = CompletableFuture.runAsync(scheduler::transitionChallengeStatus);
        awaitLockWait(running);
        return running;
    }

    private void awaitLockWait(CompletableFuture<Void> running) {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            Integer waiting = jdbc.queryForObject(
                    "SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() AND wait_event_type = 'Lock'",
                    Integer.class);
            if (waiting != null && waiting > 0) {
                return;
            }
            if (running.isDone()) {
                fail("스케줄러가 행 락을 기다리지 않고 끝났다");
            }
            sleepBriefly();
        }
        fail("10초 안에 스케줄러가 행 락을 기다리지 않았다");
    }

    private void sleepBriefly() {
        try {
            Thread.sleep(20);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
