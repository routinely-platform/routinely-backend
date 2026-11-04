package com.routinely.routine_service.application.challenge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.routinely.core.schedule.ScheduleType;
import com.routinely.jpa.config.JpaAuditingConfig;
import com.routinely.routine_service.application.event.RoutineEventPublisherImpl;
import com.routinely.routine_service.application.execution.ExecutionScheduleDeriver;
import com.routinely.routine_service.application.inbox.RoutineInboxProcessor;
import com.routinely.routine_service.domain.execution.ExecutionStatus;
import com.routinely.routine_service.domain.execution.RoutineExecutionRepository;
import com.routinely.routine_service.domain.inbox.InboxStatus;
import com.routinely.routine_service.domain.inbox.RoutineInbox;
import com.routinely.routine_service.domain.inbox.RoutineInboxRepository;
import com.routinely.routine_service.domain.outbox.RoutineOutbox;
import com.routinely.routine_service.domain.outbox.RoutineOutboxRepository;
import com.routinely.routine_service.domain.routine.Routine;
import com.routinely.routine_service.domain.routine.RoutineRepository;
import com.routinely.routine_service.infrastructure.kafka.ChallengeStartedConsumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code challenge.started} 수신 → Inbox → 처리기 → 멤버별 루틴 + Outbox, PostgreSQL에서 검증한다. (#157)
 *
 * <p>처리는 {@code processInbox()}가 <b>자기 트랜잭션으로 커밋</b>해야 롤백·재처리를 볼 수 있어 테스트 트랜잭션을 끈다.
 * 그래서 테스트마다 직접 지운다.
 */
@Import({JpaAuditingConfig.class, ChallengeStartedConsumer.class, RoutineInboxProcessor.class,
        ChallengeStartedHandler.class, RoutineEventPublisherImpl.class, ChallengeStartedIntegrationTest.Config.class})
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("challenge.started 소비 → 멤버별 챌린지 루틴 생성")
class ChallengeStartedIntegrationTest {

    /** 2026-11-09는 월요일이다 — SPECIFIC_DAYS(월·수·금)에서 시작일이 수행일이 된다. */
    private static final LocalDate START = LocalDate.of(2026, 11, 9);
    private static final LocalDate END = LocalDate.of(2026, 12, 8);

    @TestConfiguration
    static class Config {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        Clock clock() {
            return Clock.systemUTC();
        }
    }

    @Autowired ChallengeStartedConsumer consumer;
    @Autowired RoutineInboxProcessor processor;
    @Autowired RoutineInboxRepository inboxRepository;
    @Autowired RoutineRepository routineRepository;
    @Autowired RoutineOutboxRepository outboxRepository;
    @Autowired RoutineExecutionRepository executionRepository;

    @AfterEach
    void cleanUp() {
        outboxRepository.deleteAll();
        routineRepository.deleteAll();
        inboxRepository.deleteAll();
    }

    private static String message(String eventId, long challengeId, String endedAt, long... userIds) {
        String members = String.join(",", Arrays.stream(userIds)
                .mapToObj(id -> "{\"userId\":%d,\"joinedAt\":\"2026-11-01\"}".formatted(id)).toList());
        String ended = endedAt == null ? "" : "\"endedAt\":\"%s\",".formatted(endedAt);
        return """
                {"eventId":"%s","occurredAt":"2026-11-09T00:00:00Z","challengeId":%d,\
                "challengeName":"30일 러닝 챌린지","startedAt":"%s",%s\
                "routineTitle":"저녁 러닝 30분","categoryCode":"EXERCISE","scheduleType":"SPECIFIC_DAYS",\
                "daysOfWeek":["MON","WED","FRI"],"targetCount":null,"leaderUserId":%d,\
                "members":[%s]}""".formatted(eventId, challengeId, START, ended, userIds[0], members);
    }

    /** 수신 → 저장된 Inbox ID. */
    private Long receive(String message) {
        consumer.consume(message);
        return inboxRepository.findIdsByStatus(InboxStatus.RECEIVED,
                org.springframework.data.domain.PageRequest.of(0, 100)).getLast();
    }

    private List<Routine> routinesOf(long challengeId) {
        return routineRepository.findAll().stream()
                .filter(r -> r.getChallengeId() != null && r.getChallengeId() == challengeId)
                .toList();
    }

    @Test
    @DisplayName("멤버_N명이면_정의를_페이로드에서_복사한_루틴_N개를_만든다")
    void handle_createsRoutinePerMember() {
        Long inboxId = receive(message("evt-1", 5L, END.toString(), 100L, 200L, 300L));

        processor.processInbox(inboxId);

        List<Routine> routines = routinesOf(5L);
        assertThat(routines).extracting(Routine::getUserId).containsExactlyInAnyOrder(100L, 200L, 300L);
        assertThat(routines).allSatisfy(routine -> {
            assertThat(routine.getRoutineTemplateId()).isNull();
            assertThat(routine.getPreferredTime()).isNull();
            assertThat(routine.getPreferredDays()).isNull();
            assertThat(routine.isActive()).isTrue();
            assertThat(routine.getStartedAt()).isEqualTo(START);
            assertThat(routine.getEndedAt()).isEqualTo(END);
            assertThat(routine.getDefinition().getTitle()).isEqualTo("저녁 러닝 30분");
            assertThat(routine.getDefinition().getCategoryCode()).isEqualTo("EXERCISE");
            assertThat(routine.getDefinition().getScheduleType()).isEqualTo(ScheduleType.SPECIFIC_DAYS);
            // MON(bit0) | WED(bit2) | FRI(bit4) = 1 + 4 + 16
            assertThat(routine.getDefinition().getDaysOfWeek()).isEqualTo((short) 21);
            assertThat(routine.getDefinition().getTargetCount()).isNull();
        });
        assertThat(inboxRepository.findById(inboxId).orElseThrow().getStatus()).isEqualTo(InboxStatus.PROCESSED);
    }

    @Test
    @DisplayName("멤버마다_알림_예약_Outbox를_루틴_ID로_1건씩_쌓고_실행_기록은_만들지_않는다")
    void handle_publishesNotificationPerMemberWithoutExecutions() {
        Long inboxId = receive(message("evt-1", 5L, END.toString(), 100L, 200L));

        processor.processInbox(inboxId);

        List<Long> routineIds = routinesOf(5L).stream().map(Routine::getId).toList();
        List<RoutineOutbox> outboxes = outboxRepository.findAll();
        assertThat(outboxes).hasSize(2)
                .allSatisfy(outbox -> {
                    assertThat(outbox.getEventType()).isEqualTo("routine.notification.scheduled");
                    assertThat(outbox.getAggregateType()).isEqualTo("ROUTINE");
                });
        assertThat(outboxes).extracting(RoutineOutbox::getAggregateId).containsExactlyInAnyOrderElementsOf(routineIds);
        assertThat(executionRepository.count()).isZero();
    }

    @Test
    @DisplayName("생성된_루틴은_시작일에_수행_대상으로_파생되고_종료일이_있어_완료_가드를_지난다")
    void handle_createdRoutineIsDueOnStartDate() {
        Long inboxId = receive(message("evt-1", 5L, END.toString(), 100L));

        processor.processInbox(inboxId);

        Routine routine = routinesOf(5L).getFirst();
        assertThat(START.getDayOfWeek()).isEqualTo(DayOfWeek.MONDAY);
        assertThat(ExecutionScheduleDeriver.deriveStatus(routine.getDefinition(), START, START))
                .contains(ExecutionStatus.PENDING);
        assertThat(ExecutionScheduleDeriver.isCompletable(routine.getDefinition(), START.plusDays(1))).isFalse();
        assertThat(routine.hasNoEndDate()).isFalse();
    }

    @Test
    @DisplayName("endedAt이_없으면_루틴을_만들지_않고_Inbox에_실패를_기록한다")
    void handle_withoutEndedAt_createsNothingAndRecordsFailure() {
        Long inboxId = receive(message("evt-1", 5L, null, 100L, 200L));

        assertThatThrownBy(() -> processor.processInbox(inboxId))
                .hasMessageContaining("endedAt");
        processor.recordFailure(inboxId, "endedAt 없음");

        assertThat(routinesOf(5L)).isEmpty();
        assertThat(outboxRepository.count()).isZero();
        RoutineInbox inbox = inboxRepository.findById(inboxId).orElseThrow();
        assertThat(inbox.getStatus()).isEqualTo(InboxStatus.RECEIVED);
        assertThat(inbox.getRetryCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("같은_eventId를_다시_받아도_Inbox는_1행이다")
    void consume_sameEventIdTwice_storesOnce() {
        String message = message("evt-1", 5L, END.toString(), 100L);

        consumer.consume(message);
        consumer.consume(message);

        assertThat(inboxRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("처리_도중_실패하면_앞서_만든_루틴과_Outbox까지_전부_롤백된다")
    void handle_failureMidLoop_rollsBackEverything() {
        // 세 번째 멤버가 첫 번째와 같아 uq_routines_challenge_user에 걸린다 — 앞의 두 명이 남으면 안 된다.
        Long inboxId = receive(message("evt-1", 5L, END.toString(), 100L, 200L, 100L));

        assertThatThrownBy(() -> processor.processInbox(inboxId));

        assertThat(routinesOf(5L)).isEmpty();
        assertThat(outboxRepository.count()).isZero();
        assertThat(inboxRepository.findById(inboxId).orElseThrow().getStatus()).isEqualTo(InboxStatus.RECEIVED);
    }

    @Test
    @DisplayName("처리된_Inbox를_다시_처리해도_루틴이_늘지_않는다")
    void processInbox_again_doesNotDuplicate() {
        Long inboxId = receive(message("evt-1", 5L, END.toString(), 100L, 200L));

        processor.processInbox(inboxId);
        processor.processInbox(inboxId);

        assertThat(routinesOf(5L)).hasSize(2);
        assertThat(outboxRepository.count()).isEqualTo(2);
    }

    @Test
    @DisplayName("eventId가_다른_같은_챌린지의_started가_다시_오면_UNIQUE가_막아_루틴이_늘지_않는다")
    void handle_sameChallengeDifferentEventId_blockedByUniqueIndex() {
        processor.processInbox(receive(message("evt-1", 5L, END.toString(), 100L, 200L)));
        Long secondId = receive(message("evt-2", 5L, END.toString(), 100L, 200L));

        assertThatThrownBy(() -> processor.processInbox(secondId));

        assertThat(routinesOf(5L)).hasSize(2);
        assertThat(outboxRepository.count()).isEqualTo(2);
        assertThat(inboxRepository.findById(secondId).orElseThrow().getStatus()).isEqualTo(InboxStatus.RECEIVED);
    }
}
