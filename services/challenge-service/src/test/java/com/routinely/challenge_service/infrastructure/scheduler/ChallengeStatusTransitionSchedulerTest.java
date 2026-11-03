package com.routinely.challenge_service.infrastructure.scheduler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.routinely.challenge_service.application.dto.CreateChallengeCommand;
import com.routinely.challenge_service.domain.challenge.Challenge;
import com.routinely.challenge_service.domain.challenge.ChallengeLifecycleStatus;
import com.routinely.challenge_service.domain.challenge.ChallengeRepository;
import com.routinely.challenge_service.domain.challenge.ChallengeRoutineDefinition;
import com.routinely.challenge_service.domain.member.ChallengeMember;
import com.routinely.challenge_service.domain.member.ChallengeMemberRepository;
import com.routinely.challenge_service.domain.member.MembershipStatus;
import com.routinely.challenge_service.domain.outbox.ChallengeOutbox;
import com.routinely.challenge_service.domain.outbox.ChallengeOutboxRepository;
import com.routinely.challenge_service.infrastructure.kafka.KafkaTopic;
import com.routinely.core.schedule.ScheduleType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
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
    private ChallengeMemberRepository challengeMemberRepository;
    private ChallengeOutboxRepository challengeOutboxRepository;
    private ObjectMapper objectMapper;
    private ChallengeStatusTransitionScheduler scheduler;

    @BeforeEach
    void setUp() {
        challengeRepository = mock(ChallengeRepository.class);
        challengeMemberRepository = mock(ChallengeMemberRepository.class);
        challengeOutboxRepository = mock(ChallengeOutboxRepository.class);
        objectMapper = new ObjectMapper();
        scheduler = new ChallengeStatusTransitionScheduler(
                challengeRepository,
                challengeMemberRepository,
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
        stubActiveMembers(challenge, ChallengeMember.createLeader(challenge, 100L, LocalDateTime.of(2026, 6, 1, 23, 30)));

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
        assertThat(payload.get("routineTitle").asText()).isEqualTo("아침 러닝 30분");
        assertThat(payload.get("categoryCode").asText()).isEqualTo("EXERCISE");
        assertThat(payload.get("scheduleType").asText()).isEqualTo("DAILY");
        assertThat(payload.get("daysOfWeek").isNull()).isTrue();
        assertThat(payload.get("targetCount").isNull()).isTrue();
        assertThat(payload.has("routineTemplateId")).isFalse();
    }

    @Test
    @DisplayName("started_페이로드에_요일코드리스트와_시작시점ACTIVE멤버의참여일_방장을싣는다")
    void transitionChallengeStatus_startedPayloadCarriesDefinitionMembersAndLeader() throws Exception {
        LocalDate today = LocalDate.of(2026, 6, 8);
        Challenge challenge = challenge(3L, "월수금 러닝", ChallengeLifecycleStatus.WAITING,
                today, LocalDate.of(2026, 7, 7));
        ReflectionTestUtils.setField(challenge, "routineDefinition",
                ChallengeRoutineDefinition.of("저녁 러닝", ScheduleType.SPECIFIC_DAYS, (short) 21, null));
        ChallengeMember leader = ChallengeMember.createLeader(challenge, 100L, LocalDateTime.of(2026, 6, 1, 23, 30));
        ChallengeMember member = ChallengeMember.createMember(challenge, 200L, LocalDateTime.of(2026, 6, 5, 8, 0));
        when(challengeRepository.findWaitingToActivate(today)).thenReturn(List.of(challenge));
        when(challengeRepository.findActiveToEnd(today)).thenReturn(List.of());
        when(challengeMemberRepository.findAllByChallengeIdAndStatusOrderByJoinedAtAscIdAsc(3L, MembershipStatus.ACTIVE))
                .thenReturn(List.of(leader, member));

        scheduler.transitionChallengeStatus();

        ArgumentCaptor<ChallengeOutbox> outboxCaptor = ArgumentCaptor.forClass(ChallengeOutbox.class);
        verify(challengeOutboxRepository).save(outboxCaptor.capture());
        JsonNode payload = objectMapper.readTree(outboxCaptor.getValue().getPayload());
        assertThat(payload.get("routineTitle").asText()).isEqualTo("저녁 러닝");
        assertThat(payload.get("scheduleType").asText()).isEqualTo("SPECIFIC_DAYS");
        assertThat(payload.get("daysOfWeek")).extracting(JsonNode::asText).containsExactly("MON", "WED", "FRI");
        assertThat(payload.get("targetCount").isNull()).isTrue();
        assertThat(payload.get("leaderUserId").asLong()).isEqualTo(100L);

        JsonNode members = payload.get("members");
        assertThat(members).hasSize(2);
        assertThat(members.get(0).get("userId").asLong()).isEqualTo(100L);
        assertThat(members.get(0).get("joinedAt").asText()).isEqualTo("2026-06-01");
        assertThat(members.get(1).get("userId").asLong()).isEqualTo(200L);
        assertThat(members.get(1).get("joinedAt").asText()).isEqualTo("2026-06-05");
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

    @Test
    @DisplayName("활성_방장이_없는_챌린지만_건너뛰고_다른_챌린지는_시작한다")
    void transitionChallengeStatus_noActiveLeader_skipsOnlyThatChallenge() {
        LocalDate today = LocalDate.of(2026, 6, 8);
        Challenge leaderless = challenge(1L, "방장 없음", ChallengeLifecycleStatus.WAITING, today, today.plusDays(29));
        Challenge normal = challenge(2L, "정상", ChallengeLifecycleStatus.WAITING, today, today.plusDays(29));
        when(challengeRepository.findWaitingToActivate(today)).thenReturn(List.of(leaderless, normal));
        when(challengeRepository.findActiveToEnd(today)).thenReturn(List.of());
        stubActiveMembers(leaderless, ChallengeMember.createMember(leaderless, 200L, today.atStartOfDay()));
        stubActiveMembers(normal, ChallengeMember.createLeader(normal, 100L, today.atStartOfDay()));

        scheduler.transitionChallengeStatus();

        assertThat(leaderless.getStatus()).isEqualTo(ChallengeLifecycleStatus.WAITING);
        assertThat(normal.getStatus()).isEqualTo(ChallengeLifecycleStatus.ACTIVE);
        ArgumentCaptor<ChallengeOutbox> outboxCaptor = ArgumentCaptor.forClass(ChallengeOutbox.class);
        verify(challengeOutboxRepository).save(outboxCaptor.capture());
        assertThat(outboxCaptor.getValue().getAggregateId()).isEqualTo(2L);
    }

    private void stubActiveMembers(Challenge challenge, ChallengeMember... members) {
        when(challengeMemberRepository.findAllByChallengeIdAndStatusOrderByJoinedAtAscIdAsc(
                challenge.getId(), MembershipStatus.ACTIVE)).thenReturn(List.of(members));
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
                        null,
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
