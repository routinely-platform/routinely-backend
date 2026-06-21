package com.routinely.challenge_service.infrastructure.scheduler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.routinely.challenge_service.application.dto.CreateChallengeCommand;
import com.routinely.challenge_service.domain.challenge.Challenge;
import com.routinely.challenge_service.domain.challenge.ChallengeLifecycleStatus;
import com.routinely.challenge_service.domain.challenge.ChallengeRepository;
import com.routinely.challenge_service.domain.outbox.ChallengeOutbox;
import com.routinely.challenge_service.domain.outbox.ChallengeOutboxRepository;
import com.routinely.challenge_service.infrastructure.kafka.KafkaTopic;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("ChallengeStatusTransitionScheduler")
class ChallengeStatusTransitionSchedulerTest {

    private static final Clock FIXED_KST_MIDNIGHT_CLOCK =
            Clock.fixed(Instant.parse("2026-06-07T15:00:00Z"), ZoneId.of("Asia/Seoul"));

    private ChallengeRepository challengeRepository;
    private ChallengeOutboxRepository challengeOutboxRepository;
    private ObjectMapper objectMapper;
    private ChallengeStatusTransitionScheduler scheduler;

    @BeforeEach
    void setUp() {
        challengeRepository = mock(ChallengeRepository.class);
        challengeOutboxRepository = mock(ChallengeOutboxRepository.class);
        objectMapper = new ObjectMapper();
        scheduler = new ChallengeStatusTransitionScheduler(
                challengeRepository,
                challengeOutboxRepository,
                objectMapper,
                FIXED_KST_MIDNIGHT_CLOCK
        );
    }

    @Test
    @DisplayName("WAITING_챌린지를_KST_오늘날짜로_ACTIVE전이하고_started_Outbox를_저장한다")
    void transitionChallengeStatus_activatesWaitingChallengesAndSavesStartedOutbox() throws Exception {
        LocalDate today = LocalDate.of(2026, 6, 8);
        Challenge challenge = challenge(
                1L,
                "30일 러닝 챌린지",
                ChallengeLifecycleStatus.WAITING,
                LocalDate.of(2026, 6, 8),
                LocalDate.of(2026, 7, 7)
        );
        when(challengeRepository.findWaitingToActivate(today)).thenReturn(List.of(challenge));
        when(challengeRepository.findActiveToEnd(today)).thenReturn(List.of());

        scheduler.transitionChallengeStatus();

        verify(challengeRepository).findWaitingToActivate(eq(today));
        verify(challengeRepository).findActiveToEnd(eq(today));
        assertThat(challenge.getStatus()).isEqualTo(ChallengeLifecycleStatus.ACTIVE);

        ArgumentCaptor<ChallengeOutbox> outboxCaptor = ArgumentCaptor.forClass(ChallengeOutbox.class);
        verify(challengeOutboxRepository).save(outboxCaptor.capture());
        ChallengeOutbox outbox = outboxCaptor.getValue();
        assertThat(outbox.getAggregateType()).isEqualTo("challenge");
        assertThat(outbox.getAggregateId()).isEqualTo(1L);
        assertThat(outbox.getEventType()).isEqualTo(KafkaTopic.CHALLENGE_STARTED);
        assertThat(outbox.getIdempotencyKey()).isEqualTo("challenge.started:1");

        JsonNode payload = objectMapper.readTree(outbox.getPayload());
        assertThat(payload.get("eventId").asText()).isNotBlank();
        assertThat(payload.get("occurredAt").asText()).isEqualTo("2026-06-07T15:00:00Z");
        assertThat(payload.get("challengeId").asLong()).isEqualTo(1L);
        assertThat(payload.get("challengeName").asText()).isEqualTo("30일 러닝 챌린지");
        assertThat(payload.get("startedAt").asText()).isEqualTo("2026-06-08");
        assertThat(payload.get("endedAt").asText()).isEqualTo("2026-07-07");
    }

    @Test
    @DisplayName("ACTIVE_챌린지를_KST_오늘날짜로_ENDED전이하고_Outbox는_저장하지않는다")
    void transitionChallengeStatus_endsActiveChallengesWithoutOutbox() {
        LocalDate today = LocalDate.of(2026, 6, 8);
        Challenge challenge = challenge(
                2L,
                "아침 운동 챌린지",
                ChallengeLifecycleStatus.ACTIVE,
                LocalDate.of(2026, 5, 1),
                LocalDate.of(2026, 6, 7)
        );
        when(challengeRepository.findWaitingToActivate(today)).thenReturn(List.of());
        when(challengeRepository.findActiveToEnd(today)).thenReturn(List.of(challenge));

        scheduler.transitionChallengeStatus();

        assertThat(challenge.getStatus()).isEqualTo(ChallengeLifecycleStatus.ENDED);
        verify(challengeOutboxRepository, never()).save(any(ChallengeOutbox.class));
    }

    @Test
    @DisplayName("UTC기준_전날이어도_KST_오늘날짜를_조회조건으로_사용한다")
    void transitionChallengeStatus_usesKoreanTodayInsteadOfDatabaseCurrentDate() {
        LocalDate koreanToday = LocalDate.of(2026, 6, 8);
        when(challengeRepository.findWaitingToActivate(koreanToday)).thenReturn(List.of());
        when(challengeRepository.findActiveToEnd(koreanToday)).thenReturn(List.of());

        scheduler.transitionChallengeStatus();

        verify(challengeRepository).findWaitingToActivate(eq(koreanToday));
        verify(challengeRepository).findActiveToEnd(eq(koreanToday));
    }

    private Challenge challenge(Long id,
                                String title,
                                ChallengeLifecycleStatus status,
                                LocalDate startedAt,
                                LocalDate endedAt) {
        Challenge challenge = Challenge.create(
                new CreateChallengeCommand(
                        title,
                        "설명",
                        true,
                        10,
                        "EXERCISE",
                        startedAt,
                        endedAt,
                        "아침 러닝 30분",
                        "DAILY",
                        null
                ),
                100L,
                null
        );
        ReflectionTestUtils.setField(challenge, "id", id);
        ReflectionTestUtils.setField(challenge, "status", status);
        return challenge;
    }
}
