package com.routinely.routine_service.application.event;

import com.routinely.routine_service.domain.outbox.RoutineOutbox;
import com.routinely.routine_service.domain.outbox.RoutineOutboxRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.dao.DataIntegrityViolationException;
import static org.assertj.core.api.Assertions.*;

@org.springframework.context.annotation.Import({RoutineEventPublisherImpl.class, RoutineOutboxIntegrationTest.Config.class})
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@DisplayName("루틴 Outbox PostgreSQL 저장")
class RoutineOutboxIntegrationTest {
    @Autowired RoutineOutboxRepository repository;

    @Autowired RoutineEventPublisher publisher;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;

    @org.springframework.boot.test.context.TestConfiguration
    static class Config {
        @org.springframework.context.annotation.Bean
        com.fasterxml.jackson.databind.ObjectMapper objectMapper() {
            return new com.fasterxml.jackson.databind.ObjectMapper();
        }
        @org.springframework.context.annotation.Bean
        java.time.Clock clock() { return java.time.Clock.systemUTC(); }
    }

    private com.routinely.routine_service.domain.routine.Routine routine() {
        var routine = com.routinely.routine_service.domain.routine.Routine.forPersonal(null, 7L,
                com.routinely.routine_service.domain.definition.RoutineDefinition.of("걷기", "HEALTH",
                        com.routinely.routine_service.domain.template.ScheduleType.DAILY, null, null),
                java.time.LocalDate.of(2026, 9, 1), null, null);
        org.springframework.test.util.ReflectionTestUtils.setField(routine, "id", 10L);
        return routine;
    }

    @Test @DisplayName("호출자 트랜잭션이 롤백되면 Outbox도 사라진다")
    @org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void publish_rollback_doesNotPersist() {
        long before = repository.count();
        new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(status -> {
            publisher.publishCompleted(routine(), 100L, java.time.LocalDate.of(2026, 9, 20), null);
            repository.flush();
            assertThat(repository.count()).isEqualTo(before + 1);
            status.setRollbackOnly();
        });
        assertThat(repository.count()).isEqualTo(before);
    }

    @Test @DisplayName("트랜잭션 없이 발행 창구를 호출하면 거절한다")
    @org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void publish_withoutTransaction_fails() {
        assertThatThrownBy(() -> publisher.publishNotificationScheduled(routine()))
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
    }

    @Test @DisplayName("다른 폴러가 잠근 가장 오래된 행은 건너뛰고 다음 행을 가져온다")
    @org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void findPendingForUpdate_skipsLockedRows() {
        var first = repository.saveAndFlush(event());
        var second = repository.saveAndFlush(RoutineOutbox.create("ROUTINE_EXECUTION", 101L,
                "routine.execution.completed", "{\"userId\":7}", "ROUTINE_EXECUTION:101:completed", "7",
                java.time.LocalDateTime.of(2000, 1, 1, 0, 0, 1)));
        try {
            new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(outer -> {
                assertThat(repository.findPendingForUpdate(1)).extracting(RoutineOutbox::getId)
                        .containsExactly(first.getId());
                var inner = new org.springframework.transaction.support.TransactionTemplate(transactions);
                inner.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                inner.executeWithoutResult(ignored ->
                        assertThat(repository.findPendingForUpdate(1)).extracting(RoutineOutbox::getId)
                                .containsExactly(second.getId()));
            });
        } finally {
            repository.deleteAllById(java.util.List.of(first.getId(), second.getId()));
        }
    }

    private RoutineOutbox event() {
        return RoutineOutbox.create("ROUTINE_EXECUTION", 100L, "routine.execution.completed",
                "{\"userId\":7}", "ROUTINE_EXECUTION:100:completed", "7",
                java.time.LocalDateTime.of(2000, 1, 1, 0, 0));
    }

    @Test @DisplayName("V6 마이그레이션 이후 폴링 쿼리와 시퀀스가 동작한다")
    void findPendingForUpdate_returnsSavedEvent() {
        var event = repository.saveAndFlush(event());
        assertThat(repository.findPendingForUpdate(100)).extracting(RoutineOutbox::getId).contains(event.getId());
        long first = repository.nextRevision();
        assertThat(repository.nextRevision()).isGreaterThan(first);
    }

    @Test @DisplayName("같은 멱등성 키를 두 번 저장할 수 없다")
    void save_duplicateKey_fails() {
        repository.saveAndFlush(event());
        assertThatThrownBy(() -> repository.saveAndFlush(event())).isInstanceOf(DataIntegrityViolationException.class);
    }
}
