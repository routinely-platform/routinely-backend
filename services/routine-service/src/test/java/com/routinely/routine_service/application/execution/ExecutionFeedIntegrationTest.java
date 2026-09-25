package com.routinely.routine_service.application.execution;

import com.routinely.core.exception.BusinessException;
import com.routinely.jpa.config.JpaAuditingConfig;
import com.routinely.routine_service.application.execution.dto.CompleteExecutionCommand;
import com.routinely.routine_service.domain.definition.RoutineDefinition;
import com.routinely.routine_service.domain.execution.RoutineExecutionRepository;
import com.routinely.routine_service.domain.feed.FeedCardRepository;
import com.routinely.routine_service.domain.routine.Routine;
import com.routinely.routine_service.domain.routine.RoutineRepository;
import com.routinely.routine_service.domain.template.RoutineTemplate;
import com.routinely.routine_service.domain.template.RoutineTemplateRepository;
import com.routinely.routine_service.domain.template.ScheduleType;
import com.routinely.storage.FileStorage;
import com.routinely.storage.StoredFile;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import({JpaAuditingConfig.class, RoutineExecutionServiceImpl.class, AcceptedCountCalculator.class,
        com.routinely.routine_service.application.routine.RoutineServiceImpl.class,
        com.routinely.routine_service.application.event.RoutineEventPublisherImpl.class, ExecutionFeedIntegrationTest.Config.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("인증과 피드 카드 트랜잭션")
class ExecutionFeedIntegrationTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 13);
    @Autowired RoutineExecutionService service;
    @Autowired com.routinely.routine_service.application.routine.RoutineService routineService;
    @Autowired RoutineRepository routines;
    @Autowired RoutineTemplateRepository templates;
    @Autowired RoutineExecutionRepository executions;
    @Autowired FeedCardRepository cards;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired FileStorage storage;

    @TestConfiguration
    static class Config {
        @Bean com.fasterxml.jackson.databind.ObjectMapper objectMapper() { return new com.fasterxml.jackson.databind.ObjectMapper(); }
        @Bean Clock clock() { return Clock.fixed(Instant.parse("2026-09-13T03:00:00Z"), ZoneId.of("Asia/Seoul")); }
        @Bean FileStorage fileStorage() { return mock(FileStorage.class); }
    }

    @BeforeEach
    @AfterEach
    void cleanDatabase() {
        jdbc.update("DELETE FROM routine_outbox");
        jdbc.update("DELETE FROM feed_reactions");
        jdbc.update("DELETE FROM feed_cards");
        jdbc.update("DELETE FROM routine_executions");
        jdbc.update("DELETE FROM routines");
        jdbc.update("DELETE FROM routine_templates");
        reset(storage);
    }

    private Long routine() {
        return routines.saveAndFlush(Routine.forPersonal(null, 1L,
                RoutineDefinition.of("물 한 잔", "HEALTH", ScheduleType.DAILY, null, null),
                TODAY.minusDays(10), null, null)).getId();
    }
    private CompleteExecutionCommand command(Long id, boolean photo) {
        return new CompleteExecutionCommand(id, 1L, TODAY, photo ? "a.jpg" : null,
                photo ? "image/jpeg" : null, photo ? new byte[]{(byte)255, (byte)216, (byte)255} : null, "기록");
    }

    @Test @DisplayName("다중 완료는 선택한 세 루틴마다 카드 한 장을 만든다")
    void bulkComplete_createsThreeCards() {
        var ids = List.of(routine(), routine(), routine());
        var result = service.bulkComplete(1L, TODAY, ids);
        assertThat(result).hasSize(3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM routine_outbox", Integer.class)).isEqualTo(3);
        assertThat(executions.count()).isEqualTo(3);
        assertThat(cards.findAll()).hasSize(3).allSatisfy(card -> {
            assertThat(card.getRoutineTitle()).isEqualTo("물 한 잔");
            assertThat(card.getScheduledDate()).isEqualTo(TODAY);
            assertThat(card.getPhotoUrl()).isNull();
        });
        assertThat(result).allSatisfy(row -> assertThat(row.feedCardId()).isNotNull());
    }

    @Test @DisplayName("다중 완료 중 뒤의 루틴이 실패하면 앞서 저장한 실행과 카드도 롤백한다")
    void bulkComplete_failureRollsBackAll() {
        Long active = routine();
        Long stopped = routine();
        jdbc.update("UPDATE routines SET is_active = false WHERE id = ?", stopped);
        assertThatThrownBy(() -> service.bulkComplete(1L, TODAY, List.of(active, stopped)))
                .isInstanceOf(BusinessException.class);
        assertThat(executions.count()).isZero();
        assertThat(cards.count()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM routine_outbox", Integer.class)).isZero();
    }

    @Test @DisplayName("백필 완료 후 취소는 삭제를 반영해 캡을 다시 계산하고 재완료는 새 실행 이벤트를 만든다")
    void completeAndCancel_recomputesCapAndRevision() {
        Long id = routine();
        jdbc.update("UPDATE routines SET challenge_id = 5, ended_at = ?, schedule_type = 'WEEKLY_COUNT', target_count = 3 WHERE id = ?", TODAY, id);
        for (int day = 6; day <= 10; day++) {
            service.complete(new CompleteExecutionCommand(id, 1L, LocalDate.of(2026, 9, day), null, null, null, null));
        }
        assertThat(jdbc.queryForList("SELECT (payload->>'acceptedCount')::int FROM routine_outbox ORDER BY id", Integer.class))
                .containsExactly(1, 2, 3, 3, 3);
        service.cancelComplete(id, 1L, LocalDate.of(2026, 9, 10));
        assertThat(jdbc.queryForObject("SELECT (payload->>'acceptedCount')::int FROM routine_outbox ORDER BY id DESC LIMIT 1", Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT event_type FROM routine_outbox ORDER BY id DESC LIMIT 1", String.class)).isEqualTo("routine.execution.cancelled");
        service.complete(new CompleteExecutionCommand(id, 1L, LocalDate.of(2026, 9, 10), null, null, null, null));
        assertThat(jdbc.queryForList("SELECT (payload->>'revision')::bigint FROM routine_outbox ORDER BY id", Long.class)).isSorted().doesNotHaveDuplicates();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM routine_outbox", Integer.class)).isEqualTo(7);
    }

    @Test @DisplayName("도달 시각은 PostgreSQL의 completed_at 순서로 계산하고, 캡 안 기록을 취소하면 초과 기록이 도달 시각이 된다")
    void cancelInsideCap_movesReachedAtToPromotedRecord() {
        Long id = routine();
        jdbc.update("UPDATE routines SET challenge_id = 5, ended_at = ?, schedule_type = 'WEEKLY_COUNT', target_count = 3 WHERE id = ?", TODAY, id);
        // 9/6(일)~9/12(토) 같은 주. 월·화·수·금을 각 날짜 09:00(KST)에 누른 것으로 맞춘다.
        for (int day : new int[]{7, 8, 9, 11}) {
            LocalDate date = LocalDate.of(2026, 9, day);
            service.complete(new CompleteExecutionCommand(id, 1L, date, null, null, null, null));
            jdbc.update("UPDATE routine_executions SET completed_at = ? WHERE routine_id = ? AND scheduled_date = ?",
                    date.atTime(9, 0), id, date);
        }
        // 화요일(캡 안) 취소 → 남은 월·수·금으로 3회, 금요일 09:00 KST에 채웠다
        service.cancelComplete(id, 1L, LocalDate.of(2026, 9, 8));

        var last = jdbc.queryForMap("SELECT event_type, (payload->>'acceptedCount')::int AS cnt, payload->>'reachedAt' AS reached "
                + "FROM routine_outbox ORDER BY id DESC LIMIT 1");
        assertThat(last.get("event_type")).isEqualTo("routine.execution.cancelled");
        assertThat(last.get("cnt")).isEqualTo(3);
        assertThat(last.get("reached")).isEqualTo("2026-09-11T00:00:00Z");
    }

    @Test @DisplayName("루틴 시작과 선호 시각 해제 및 중단은 각각 알림 스냅샷을 저장한다")
    void routineLifecycle_publishesNotificationSnapshots() {
        var template = templates.saveAndFlush(RoutineTemplate.forPersonal(1L,
                RoutineDefinition.of("걷기", "HEALTH", ScheduleType.DAILY, null, null)));
        routineService.start(new com.routinely.routine_service.application.routine.dto.StartRoutineCommand(
                1L, template.getId(), TODAY, null, LocalTime.of(7, 0)));
        Long id = jdbc.queryForObject("SELECT id FROM routines", Long.class);
        routineService.update(id, 1L, new com.routinely.routine_service.application.routine.dto.UpdateRoutineCommand(
                "새 제목", null, null, null, null, null, null, null, null, false, true, true));
        routineService.stop(id, 1L);
        assertThat(jdbc.queryForList("SELECT payload->>'preferredTime' FROM routine_outbox ORDER BY id", String.class))
                .containsExactly("07:00", null, null);
        assertThat(jdbc.queryForList("SELECT payload->>'active' FROM routine_outbox ORDER BY id", String.class))
                .containsExactly("true", "true", "false");
        assertThat(jdbc.queryForList("SELECT event_type FROM routine_outbox", String.class))
                .containsOnly("routine.notification.scheduled").hasSize(3);
    }

    @Test @DisplayName("빈 목록의 다중 완료도 정상 처리한다")
    void bulkComplete_emptyListReturnsZero() {
        assertThat(service.bulkComplete(1L, TODAY, List.of())).isEmpty();
        assertThat(cards.count()).isZero();
    }

    @Test @DisplayName("리액션이 있어도 취소하면 카드와 실행이 삭제되고 커밋 후 사진을 지운다")
    void cancel_cascadesReactionsAndDeletesPhotoAfterCommit() {
        when(storage.upload(any())).thenReturn(new StoredFile("photo-key", "https://cdn/a.jpg"));
        Long id = routine();
        var completed = service.complete(command(id, true));
        jdbc.update("INSERT INTO feed_reactions(feed_card_id, user_id, emoji) VALUES (?, 2, '🔥')", completed.feedCardId());
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            service.cancelComplete(id, 1L, TODAY);
            verify(storage, never()).delete(any());
        });
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM feed_reactions", Long.class)).isZero();
        assertThat(cards.count()).isZero();
        assertThat(executions.count()).isZero();
        verify(storage).delete("photo-key");
    }

    @Test @DisplayName("취소 트랜잭션이 롤백되면 카드와 실행과 사진을 유지한다")
    void cancel_rollbackKeepsPhoto() {
        when(storage.upload(any())).thenReturn(new StoredFile("photo-key", "https://cdn/a.jpg"));
        Long id = routine();
        service.complete(command(id, true));
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            service.cancelComplete(id, 1L, TODAY);
            tx.setRollbackOnly();
        });
        assertThat(cards.count()).isEqualTo(1);
        assertThat(executions.count()).isEqualTo(1);
        verify(storage, never()).delete(any());
    }

    @Test @DisplayName("카드 저장 실패로 완료가 롤백되면 업로드 사진을 보상 삭제한다")
    void complete_cardFailureCompensatesPhoto() {
        when(storage.upload(any())).thenReturn(new StoredFile("photo-key", "x".repeat(501)));
        Long id = routine();
        assertThatThrownBy(() -> service.complete(command(id, true)))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(executions.count()).isZero();
        assertThat(cards.count()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM routine_outbox", Integer.class)).isZero();
        verify(storage).delete("photo-key");
    }

    @Test @DisplayName("카드보다 실행을 먼저 삭제하면 외래키가 잘못된 순서를 차단한다")
    void delete_executionBeforeCardViolatesForeignKey() {
        var completed = service.complete(command(routine(), false));
        assertThatThrownBy(() -> jdbc.update("DELETE FROM routine_executions WHERE id = ?", completed.executionId()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(cards.count()).isEqualTo(1);
    }

    @Test @DisplayName("템플릿 변경과 삭제 후에도 루틴 스케줄이 유지되고 제목 수정 후에도 피드 스냅샷은 유지된다")
    void complete_templateChangesDoNotRewriteHistory() {
        var template = templates.saveAndFlush(RoutineTemplate.forPersonal(1L,
                RoutineDefinition.of("원래 제목", "HEALTH", ScheduleType.DAILY, null, null)));
        var routine = routines.saveAndFlush(Routine.forPersonal(template.getId(), 1L,
                template.getDefinition().copy(), TODAY.minusDays(10), null, null));
        jdbc.update("UPDATE routine_templates SET title = '새 템플릿', schedule_type = 'WEEKLY_COUNT', target_count = 2, is_deleted = true WHERE id = ?", template.getId());
        var completed = service.complete(command(routine.getId(), false));
        jdbc.update("UPDATE routines SET title = '새 루틴 제목' WHERE id = ?", routine.getId());
        assertThat(cards.findById(completed.feedCardId()).orElseThrow().getRoutineTitle()).isEqualTo("원래 제목");
        assertThat(service.getMyExecutions(1L, routine.getId(), null, TODAY.minusDays(1), TODAY)).hasSize(2);
    }

    @Test @DisplayName("템플릿과 루틴의 두 CHECK 모두 주간 7회와 월간 29회를 차단한다")
    void scheduleChecks_enforceBothUpperBounds() {
        var template = templates.saveAndFlush(RoutineTemplate.forPersonal(1L,
                RoutineDefinition.of("원래 제목", "HEALTH", ScheduleType.DAILY, null, null)));
        Long id = routine();
        for (String table : List.of("routine_templates", "routines")) {
            Long targetId = table.equals("routines") ? id : template.getId();
            assertThat(jdbc.update("UPDATE " + table + " SET schedule_type = 'WEEKLY_COUNT', target_count = 6 WHERE id = ?", targetId)).isEqualTo(1);
            assertThat(jdbc.update("UPDATE " + table + " SET schedule_type = 'MONTHLY_COUNT', target_count = 28 WHERE id = ?", targetId)).isEqualTo(1);
            assertThatThrownBy(() -> jdbc.update("UPDATE " + table + " SET schedule_type = 'WEEKLY_COUNT', target_count = 7 WHERE id = ?", targetId))
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> jdbc.update("UPDATE " + table + " SET schedule_type = 'MONTHLY_COUNT', target_count = 29 WHERE id = ?", targetId))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
    }
}
