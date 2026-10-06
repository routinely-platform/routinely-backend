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
    private com.routinely.challenge_service.domain.member.ChallengeMemberRepository memberRepository;
    private com.routinely.challenge_service.domain.member.ChallengeMember member;

    @BeforeEach
    void setUp() {
        inboxRepository = mock(ChallengeInboxRepository.class);
        summaryRepository = mock(ChallengeMemberSummaryRepository.class);
        rankingRedisRepository = mock(ChallengeRankingRedisRepository.class);
        Clock clock = Clock.fixed(Instant.parse("2026-06-30T00:00:00Z"), ZoneOffset.UTC);
        memberRepository = mock(com.routinely.challenge_service.domain.member.ChallengeMemberRepository.class);
        member = mock(com.routinely.challenge_service.domain.member.ChallengeMember.class);
        when(member.getStatus()).thenReturn(com.routinely.challenge_service.domain.member.MembershipStatus.ACTIVE);
        when(memberRepository.findLockedByChallengeIdAndUserId(CHALLENGE_ID, USER_ID)).thenReturn(Optional.of(member));
        processor = new ChallengeRankingInboxProcessor(
                inboxRepository, summaryRepository, rankingRedisRepository, memberRepository, new ObjectMapper(), clock);
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
        assertThat(captor.getValue().getAcceptedCount()).isZero();
        verify(rankingRedisRepository).updateScore(CHALLENGE_ID, USER_ID, 0.0);
        assertThat(inbox.getStatus()).isEqualTo(InboxStatus.PROCESSED);
    }

    @Test
    @DisplayName("방장(LEADER)의 member.joined도 0회 집계 행과 ZSET 0점을 만든다 — 첫 인증 전에도 랭킹에 보인다")
    void processMemberJoined_leader_seedsZeroSummaryAndZsetEntry() {
        String payload = """
                {"eventId":"evt-leader","occurredAt":"2026-06-30T00:00:00Z","challengeId":7,
                 "challengeName":"5km 러닝","userId":42,"role":"LEADER"}""";
        ChallengeInbox inbox = receivedInbox(KafkaTopic.CHALLENGE_MEMBER_JOINED, payload);
        when(inboxRepository.findById(1L)).thenReturn(Optional.of(inbox));
        when(summaryRepository.findByChallengeIdAndUserId(CHALLENGE_ID, USER_ID)).thenReturn(Optional.empty());

        processor.processInbox(1L);

        ArgumentCaptor<ChallengeMemberSummary> captor = ArgumentCaptor.forClass(ChallengeMemberSummary.class);
        verify(summaryRepository).save(captor.capture());
        assertThat(captor.getValue().getAcceptedCount()).isZero();
        assertThat(captor.getValue().getLastCompletedAt()).isNull();
        // 방장 행은 생성 트랜잭션에서 먼저 커밋되므로 잠금 조회가 성공한다
        verify(memberRepository).findLockedByChallengeIdAndUserId(CHALLENGE_ID, USER_ID);
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
                 "acceptedCount":10,"reachedAt":"2026-06-29T00:00:00Z","revision":12}""";
        ChallengeInbox inbox = receivedInbox(KafkaTopic.ROUTINE_EXECUTION_COMPLETED, payload);
        when(inboxRepository.findById(1L)).thenReturn(Optional.of(inbox));
        when(summaryRepository.findByChallengeIdAndUserId(CHALLENGE_ID, USER_ID)).thenReturn(Optional.empty());

        processor.processInbox(1L);

        ArgumentCaptor<ChallengeMemberSummary> captor = ArgumentCaptor.forClass(ChallengeMemberSummary.class);
        verify(summaryRepository).save(captor.capture());
        ChallengeMemberSummary saved = captor.getValue();
        assertThat(saved.getAcceptedCount()).isEqualTo(10);
        assertThat(saved.getRevision()).isEqualTo(12);
        assertThat(saved.getLastCompletedAt()).isEqualTo(java.time.LocalDateTime.of(2026, 6, 29, 0, 0));
        verify(rankingRedisRepository).updateScore(CHALLENGE_ID, USER_ID, 10.0);
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

    @Test @DisplayName("낮거나 같은 revision은 summary와 Redis를 되돌리지 않는다")
    void processInbox_staleRevision_skips() {
        var summary = ChallengeMemberSummary.create(CHALLENGE_ID, USER_ID);
        summary.applyAcceptedCount(120, 12, java.time.LocalDateTime.of(2026, 6, 30, 0, 0));
        when(summaryRepository.findByChallengeIdAndUserId(CHALLENGE_ID, USER_ID)).thenReturn(Optional.of(summary));
        for (long revision : new long[]{11, 12}) {
            var inbox = receivedInbox(KafkaTopic.ROUTINE_EXECUTION_CANCELLED,
                    "{\"challengeId\":7,\"userId\":42,\"acceptedCount\":1,\"reachedAt\":\"2026-06-28T00:00:00Z\",\"revision\":" + revision
                            + ",\"occurredAt\":\"2026-06-29T00:00:00Z\"}");
            when(inboxRepository.findById(1L)).thenReturn(Optional.of(inbox));
            processor.processInbox(1L);
            assertThat(inbox.getStatus()).isEqualTo(InboxStatus.PROCESSED);
        }
        assertThat(summary.getAcceptedCount()).isEqualTo(120);
        org.mockito.Mockito.verifyNoInteractions(rankingRedisRepository);
        verify(summaryRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test @DisplayName("탈퇴 멤버의 최신 취소 이벤트는 집계만 갱신하고 ZSET을 복원하지 않는다")
    void processInbox_leftMember_updatesOnlySummary() {
        when(member.getStatus()).thenReturn(com.routinely.challenge_service.domain.member.MembershipStatus.LEFT);
        var inbox = receivedInbox(KafkaTopic.ROUTINE_EXECUTION_CANCELLED,
                "{\"challengeId\":7,\"userId\":42,\"acceptedCount\":101,\"reachedAt\":\"2026-06-29T00:00:00Z\",\"revision\":13,\"occurredAt\":\"2026-06-30T00:00:00Z\"}");
        when(inboxRepository.findById(1L)).thenReturn(Optional.of(inbox));
        processor.processInbox(1L);
        var captor = ArgumentCaptor.forClass(ChallengeMemberSummary.class);
        verify(summaryRepository).save(captor.capture());
        assertThat(captor.getValue().getAcceptedCount()).isEqualTo(101);
        org.mockito.Mockito.verifyNoInteractions(rankingRedisRepository);
    }

    @Test @DisplayName("최신 취소 이벤트는 감소한 인정 횟수를 ZADD로 덮어쓴다")
    void processInbox_cancelled_replacesScore() {
        var summary = ChallengeMemberSummary.create(CHALLENGE_ID, USER_ID);
        summary.applyAcceptedCount(4, 10, java.time.LocalDateTime.of(2026, 6, 29, 0, 0));
        when(summaryRepository.findByChallengeIdAndUserId(CHALLENGE_ID, USER_ID)).thenReturn(Optional.of(summary));
        var inbox = receivedInbox(KafkaTopic.ROUTINE_EXECUTION_CANCELLED,
                "{\"challengeId\":7,\"userId\":42,\"acceptedCount\":3,\"reachedAt\":\"2026-06-28T00:00:00Z\",\"revision\":11,\"occurredAt\":\"2026-06-30T00:00:00Z\"}");
        when(inboxRepository.findById(1L)).thenReturn(Optional.of(inbox));
        processor.processInbox(1L);
        assertThat(summary.getAcceptedCount()).isEqualTo(3);
        verify(rankingRedisRepository).updateScore(CHALLENGE_ID, USER_ID, 3);
    }

    @Test @DisplayName("도달 시각은 추론하지 않고 스냅샷의 reachedAt을 그대로 반영한다 — 역순 도착에도 같은 결과")
    void processInbox_reachedAtFromSnapshot_orderIndependent() {
        var summary = ChallengeMemberSummary.create(CHALLENGE_ID, USER_ID);
        when(summaryRepository.findByChallengeIdAndUserId(CHALLENGE_ID, USER_ID)).thenReturn(Optional.of(summary));
        // rev11 = 금요일 캡 초과 완료(도달은 여전히 수요일), rev10 = 수요일 3회 도달 — 역순으로 도착
        for (String json : new String[]{
                "{\"challengeId\":7,\"userId\":42,\"acceptedCount\":3,\"reachedAt\":\"2026-06-24T00:00:00Z\",\"revision\":11}",
                "{\"challengeId\":7,\"userId\":42,\"acceptedCount\":3,\"reachedAt\":\"2026-06-24T00:00:00Z\",\"revision\":10}"}) {
            var inbox = receivedInbox(KafkaTopic.ROUTINE_EXECUTION_COMPLETED, json);
            when(inboxRepository.findById(1L)).thenReturn(Optional.of(inbox));
            processor.processInbox(1L);
        }

        assertThat(summary.getRevision()).isEqualTo(11);
        assertThat(summary.getLastCompletedAt()).isEqualTo(java.time.LocalDateTime.of(2026, 6, 24, 0, 0));
    }

    @Test @DisplayName("0회로 내려간 스냅샷은 도달 시각을 null로 덮어쓴다")
    void processInbox_zeroSnapshot_clearsReachedAt() {
        var summary = ChallengeMemberSummary.create(CHALLENGE_ID, USER_ID);
        summary.applyAcceptedCount(1, 10, java.time.LocalDateTime.of(2026, 6, 24, 0, 0));
        when(summaryRepository.findByChallengeIdAndUserId(CHALLENGE_ID, USER_ID)).thenReturn(Optional.of(summary));
        var inbox = receivedInbox(KafkaTopic.ROUTINE_EXECUTION_CANCELLED,
                "{\"challengeId\":7,\"userId\":42,\"acceptedCount\":0,\"reachedAt\":null,\"revision\":11}");
        when(inboxRepository.findById(1L)).thenReturn(Optional.of(inbox));

        processor.processInbox(1L);

        assertThat(summary.getAcceptedCount()).isZero();
        assertThat(summary.getLastCompletedAt()).isNull();
        verify(rankingRedisRepository).updateScore(CHALLENGE_ID, USER_ID, 0);
    }

    @Test @DisplayName("acceptedCount와 reachedAt이 짝을 이루지 않으면 재시도 대상으로 남긴다")
    void processInbox_countAndReachedAtMismatch_throws() {
        for (String json : new String[]{
                "{\"challengeId\":7,\"userId\":42,\"acceptedCount\":3,\"revision\":11}",
                "{\"challengeId\":7,\"userId\":42,\"acceptedCount\":0,\"reachedAt\":\"2026-06-24T00:00:00Z\",\"revision\":11}"}) {
            var inbox = receivedInbox(KafkaTopic.ROUTINE_EXECUTION_COMPLETED, json);
            when(inboxRepository.findById(1L)).thenReturn(Optional.of(inbox));

            assertThatThrownBy(() -> processor.processInbox(1L))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("reachedAt");
        }
        org.mockito.Mockito.verifyNoInteractions(rankingRedisRepository);
    }

    @Test @DisplayName("cancelled 필수 필드 누락 오류는 cancelled 토픽명으로 보고한다")
    void processExecutionCancelled_whenRequiredFieldMissing_reportsCancelledTopic() {
        var inbox = receivedInbox(KafkaTopic.ROUTINE_EXECUTION_CANCELLED,
                "{\"eventId\":\"evt-9\",\"challengeId\":7,\"userId\":42}");
        when(inboxRepository.findById(1L)).thenReturn(Optional.of(inbox));

        assertThatThrownBy(() -> processor.processInbox(1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(KafkaTopic.ROUTINE_EXECUTION_CANCELLED);
    }

    private ChallengeInbox receivedInbox(String eventType, String payload) {
        return ChallengeInbox.received("msg-id", eventType, payload, "CHALLENGE", CHALLENGE_ID);
    }
}
