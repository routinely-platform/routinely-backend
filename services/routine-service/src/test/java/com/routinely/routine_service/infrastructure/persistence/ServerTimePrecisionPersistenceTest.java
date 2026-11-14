package com.routinely.routine_service.infrastructure.persistence;

import com.routinely.core.schedule.ScheduleType;
import com.routinely.core.time.RoutinelyClocks;
import com.routinely.jpa.config.JpaAuditingConfig;
import com.routinely.routine_service.domain.definition.RoutineDefinition;
import com.routinely.routine_service.domain.execution.RoutineExecution;
import com.routinely.routine_service.domain.execution.RoutineExecutionRepository;
import com.routinely.routine_service.domain.inbox.RoutineInbox;
import com.routinely.routine_service.domain.inbox.RoutineInboxRepository;
import com.routinely.routine_service.domain.outbox.RoutineOutbox;
import com.routinely.routine_service.domain.outbox.RoutineOutboxRepository;
import com.routinely.routine_service.domain.routine.Routine;
import com.routinely.routine_service.domain.routine.RoutineRepository;
import com.routinely.routine_service.domain.template.RoutineTemplate;
import com.routinely.routine_service.domain.template.RoutineTemplateRepository;
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
 * <p>나노초 원천({@code …740903532})을 운영과 같은 팩토리 {@link RoutinelyClocks#micros(Clock)}에 넣고,
 * 기대값 {@code .740903}을 직접 비교한다. DB별 하위 클래스가 H2와 PostgreSQL에서 같은 시나리오를 돌린다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaAuditingConfig.class, ServerTimePrecisionPersistenceTest.ClockConfig.class})
abstract class ServerTimePrecisionPersistenceTest {

    static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    static final Instant NANO_INSTANT = Instant.parse("2026-11-13T05:58:44.740903532Z");
    static final LocalDateTime SEOUL_MICROS = LocalDateTime.parse("2026-11-13T14:58:44.740903");
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
    @Autowired RoutineTemplateRepository templates;
    @Autowired RoutineRepository routines;
    @Autowired RoutineExecutionRepository executions;
    @Autowired RoutineOutboxRepository outboxes;
    @Autowired RoutineInboxRepository inboxes;

    @Test
    @DisplayName("Auditing — 템플릿 createdAt · updatedAt이 DB 재조회 값과 같다")
    void templateAuditing_inMemoryEqualsReloaded() {
        RoutineTemplate saved = templates.saveAndFlush(template());
        entityManager.clear();

        RoutineTemplate reloaded = templates.findById(saved.getId()).orElseThrow();

        assertThat(saved.getCreatedAt()).isEqualTo(AUDITING_MICROS);
        assertThat(reloaded.getCreatedAt()).isEqualTo(saved.getCreatedAt());
        assertThat(reloaded.getUpdatedAt()).isEqualTo(saved.getUpdatedAt());
    }

    @Test
    @DisplayName("Clock 직접 생성 — 루틴 완료 응답의 completedAt이 DB 재조회 값과 같다")
    void executionCompletedAt_inMemoryEqualsReloaded() {
        RoutineTemplate template = templates.saveAndFlush(template());
        Routine routine = routines.saveAndFlush(Routine.forPersonal(template.getId(), 7L,
                template.getDefinition().copy(), LocalDate.of(2026, 11, 1), null, null));
        RoutineExecution saved = executions.saveAndFlush(RoutineExecution.completed(
                routine.getId(), 7L, LocalDate.of(2026, 11, 13), LocalDateTime.now(clock)));
        entityManager.clear();

        RoutineExecution reloaded = executions.findById(saved.getId()).orElseThrow();

        assertThat(saved.getCompletedAt()).isEqualTo(SEOUL_MICROS);
        assertThat(reloaded.getCompletedAt()).isEqualTo(saved.getCompletedAt());
    }

    @Test
    @DisplayName("Outbox created_at · Inbox received_at — 호출부 Clock 값이 DB 재조회 값과 같다")
    void outboxAndInbox_inMemoryEqualsReloaded() {
        RoutineOutbox outbox = outboxes.saveAndFlush(RoutineOutbox.create("ROUTINE_EXECUTION", 1L,
                "routine.execution.completed", "{}", "precision-outbox", "7", LocalDateTime.now(clock)));
        RoutineInbox inbox = inboxes.saveAndFlush(RoutineInbox.received(
                "precision-inbox", "challenge.started", "{}", "CHALLENGE", 1L, LocalDateTime.now(clock)));
        entityManager.clear();

        assertThat(outbox.getCreatedAt()).isEqualTo(SEOUL_MICROS);
        assertThat(outboxes.findById(outbox.getId()).orElseThrow().getCreatedAt()).isEqualTo(outbox.getCreatedAt());
        assertThat(inbox.getReceivedAt()).isEqualTo(SEOUL_MICROS);
        assertThat(inboxes.findById(inbox.getId()).orElseThrow().getReceivedAt()).isEqualTo(inbox.getReceivedAt());
    }

    private static RoutineTemplate template() {
        return RoutineTemplate.forPersonal(7L, RoutineDefinition.of("아침 러닝 30분", "EXERCISE", ScheduleType.DAILY, null, null));
    }
}
