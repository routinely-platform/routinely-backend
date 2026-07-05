package com.routinely.challenge_service.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.routinely.challenge_service.domain.inbox.ChallengeInbox;
import com.routinely.challenge_service.domain.inbox.ChallengeInboxRepository;
import com.routinely.challenge_service.domain.inbox.InboxStatus;
import com.routinely.challenge_service.domain.summary.ChallengeMemberSummary;
import com.routinely.challenge_service.domain.summary.ChallengeMemberSummaryRepository;
import com.routinely.challenge_service.infrastructure.kafka.KafkaTopic;
import com.routinely.challenge_service.infrastructure.redis.ChallengeRankingRedisRepository;
import com.routinely.core.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("ChallengeRankingInboxProcessor")
class ChallengeRankingInboxProcessorTest {

    private static final long CHALLENGE_ID = 7L;
    private static final long USER_ID = 42L;

    private ChallengeInboxRepository inboxRepository;
    private ChallengeMemberSummaryRepository summaryRepository;
    private ChallengeRankingRedisRepository rankingRedisRepository;
    private ChallengeRankingInboxProcessor processor;

    @BeforeEach
    void setUp() {
        inboxRepository = mock(ChallengeInboxRepository.class);
        summaryRepository = mock(ChallengeMemberSummaryRepository.class);
        rankingRedisRepository = mock(ChallengeRankingRedisRepository.class);
        Clock clock = Clock.fixed(Instant.parse("2026-06-30T00:00:00Z"), ZoneOffset.UTC);
        processor = new ChallengeRankingInboxProcessor(
                inboxRepository, summaryRepository, rankingRedisRepository, new ObjectMapper(), clock);
    }

    @Test
    @DisplayName("member.joined 처리 시 집계 행이 없으면 0% 행을 생성하고 ZSET 점수를 0으로 등록한다")
    void processMemberJoined_whenSummaryAbsent_createsZeroSummaryAndZsetEntry() {
        String payload = """
                {"eventId":"evt-1","occurredAt":"2026-06-30T00:00:00Z","challengeId":7,
                 "challengeName":"5km 러닝","userId":42,"role":"MEMBER"}""";
        ChallengeInbox inbox = receivedInbox(KafkaTopic.CHALLENGE_MEMBER_JOINED, payload);
        when(inboxRepository.findById(1L)).thenReturn(Optional.of(inbox));
        when(summaryRepository.findByChallengeIdAndUserId(CHALLENGE_ID, USER_ID)).thenReturn(Optional.empty());

        processor.processInbox(1L);

        ArgumentCaptor<ChallengeMemberSummary> captor = ArgumentCaptor.forClass(ChallengeMemberSummary.class);
        verify(summaryRepository).save(captor.capture());
        assertThat(captor.getValue().getChallengeId()).isEqualTo(CHALLENGE_ID);
        assertThat(captor.getValue().getUserId()).isEqualTo(USER_ID);
        assertThat(captor.getValue().getAchievementRate()).isEqualByComparingTo(BigDecimal.ZERO);
        verify(rankingRedisRepository).updateScore(CHALLENGE_ID, USER_ID, 0.0);
        assertThat(inbox.getStatus()).isEqualTo(InboxStatus.PROCESSED);
    }

    @Test
    @DisplayName("member.joined 처리 시 집계 행이 이미 있으면 점수를 0으로 되돌리지 않는다")
    void processMemberJoined_whenSummaryExists_doesNotResetScore() {
        String payload = """
                {"eventId":"evt-1","occurredAt":"2026-06-30T00:00:00Z","challengeId":7,
                 "challengeName":"5km 러닝","userId":42,"role":"MEMBER"}""";
        ChallengeInbox inbox = receivedInbox(KafkaTopic.CHALLENGE_MEMBER_JOINED, payload);
        when(inboxRepository.findById(1L)).thenReturn(Optional.of(inbox));
        when(summaryRepository.findByChallengeIdAndUserId(CHALLENGE_ID, USER_ID))
                .thenReturn(Optional.of(ChallengeMemberSummary.create(CHALLENGE_ID, USER_ID)));

        processor.processInbox(1L);

        verify(summaryRepository, never()).save(org.mockito.ArgumentMatchers.any());
        verify(rankingRedisRepository, never())
                .updateScore(org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.anyDouble());
        assertThat(inbox.getStatus()).isEqualTo(InboxStatus.PROCESSED);
    }

    @Test
    @DisplayName("routine.execution.completed 처리 시 payload 집계값으로 summary를 갱신하고 ZSET에 달성률을 반영한다")
    void processExecutionCompleted_upsertsSummaryAndUpdatesZsetWithRate() {
        String payload = """
                {"eventId":"evt-2","occurredAt":"2026-06-30T00:00:00Z","challengeId":7,"userId":42,
                 "completedCount":10,"totalScheduled":12,"achievementRate":83.33}""";
        ChallengeInbox inbox = receivedInbox(KafkaTopic.ROUTINE_EXECUTION_COMPLETED, payload);
        when(inboxRepository.findById(1L)).thenReturn(Optional.of(inbox));
        when(summaryRepository.findByChallengeIdAndUserId(CHALLENGE_ID, USER_ID)).thenReturn(Optional.empty());

        processor.processInbox(1L);

        ArgumentCaptor<ChallengeMemberSummary> captor = ArgumentCaptor.forClass(ChallengeMemberSummary.class);
        verify(summaryRepository).save(captor.capture());
        ChallengeMemberSummary saved = captor.getValue();
        assertThat(saved.getCompletedCount()).isEqualTo(10);
        assertThat(saved.getTotalScheduled()).isEqualTo(12);
        assertThat(saved.getAchievementRate()).isEqualByComparingTo(new BigDecimal("83.33"));
        verify(rankingRedisRepository).updateScore(CHALLENGE_ID, USER_ID, 83.33);
        assertThat(inbox.getStatus()).isEqualTo(InboxStatus.PROCESSED);
    }

    @Test
    @DisplayName("routine.execution.completed 필수 집계 필드가 누락되면 예외를 던져 재시도 대상으로 남긴다")
    void processExecutionCompleted_whenRequiredFieldMissing_throws() {
        String payload = """
                {"eventId":"evt-3","occurredAt":"2026-06-30T00:00:00Z","challengeId":7,"userId":42,
                 "completedCount":10}""";
        ChallengeInbox inbox = receivedInbox(KafkaTopic.ROUTINE_EXECUTION_COMPLETED, payload);
        when(inboxRepository.findById(1L)).thenReturn(Optional.of(inbox));

        assertThatThrownBy(() -> processor.processInbox(1L))
                .isInstanceOf(BusinessException.class);
        verify(rankingRedisRepository, never())
                .updateScore(org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.anyDouble());
    }

    @Test
    @DisplayName("이미 PROCESSED 상태면 재처리하지 않는다")
    void processInbox_whenAlreadyProcessed_skips() {
        ChallengeInbox inbox = receivedInbox(KafkaTopic.CHALLENGE_MEMBER_JOINED, "{}");
        inbox.markProcessed(Instant.parse("2026-06-30T00:00:00Z").atZone(ZoneOffset.UTC).toLocalDateTime());
        when(inboxRepository.findById(1L)).thenReturn(Optional.of(inbox));

        processor.processInbox(1L);

        verify(summaryRepository, never())
                .findByChallengeIdAndUserId(org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.anyLong());
    }

    private ChallengeInbox receivedInbox(String eventType, String payload) {
        return ChallengeInbox.received("msg-id", eventType, payload, "CHALLENGE", CHALLENGE_ID);
    }
}
