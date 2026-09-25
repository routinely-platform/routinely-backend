package com.routinely.routine_service.application.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.routinely.routine_service.application.execution.AcceptedCount;
import com.routinely.routine_service.domain.definition.RoutineDefinition;
import com.routinely.routine_service.domain.outbox.RoutineOutbox;
import com.routinely.routine_service.domain.outbox.RoutineOutboxRepository;
import com.routinely.routine_service.domain.routine.Routine;
import com.routinely.routine_service.domain.template.ScheduleType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("루틴 이벤트 발행 계약")
class RoutineEventPublisherTest {
    @Mock RoutineOutboxRepository repository;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Clock clock = Clock.fixed(Instant.parse("2026-09-23T00:00:00Z"), ZoneOffset.UTC);

    private Routine routine() {
        var routine = Routine.forPersonal(null, 7L,
                RoutineDefinition.of("걷기", "HEALTH", ScheduleType.DAILY, null, null),
                LocalDate.of(2026, 9, 1), null, null);
        ReflectionTestUtils.setField(routine, "id", 10L);
        return routine;
    }

    private RoutineEventPublisher publisher() {
        return new RoutineEventPublisherImpl(repository, mapper, clock);
    }

    private RoutineOutbox saved() {
        var captor = ArgumentCaptor.forClass(RoutineOutbox.class);
        verify(repository).save(captor.capture());
        return captor.getValue();
    }

    @Test @DisplayName("개인 완료는 랭킹 필드를 생략하고 사용자 키로 저장한다")
    void publishCompleted_personal_omitsRanking() throws Exception {
        publisher().publishCompleted(routine(), 100L, LocalDate.of(2026, 9, 20), null);
        var outbox = saved();
        var json = mapper.readTree(outbox.getPayload());
        assertThat(outbox.getPartitionKey()).isEqualTo("7");
        assertThat(outbox.getAggregateId()).isEqualTo(100L);
        assertThat(outbox.getIdempotencyKey()).isEqualTo("ROUTINE_EXECUTION:100:completed");
        assertThat(json.has("acceptedCount")).isFalse();
        assertThat(json.has("reachedAt")).isFalse();
        assertThat(json.has("revision")).isFalse();
        assertThat(json.path("execDate").asText()).isEqualTo("2026-09-20");
        assertThat(json.path("occurredAt").asText()).isEqualTo("2026-09-23T00:00:00Z");
        verify(repository, never()).nextRevision();
    }

    @Test @DisplayName("챌린지 취소는 재계산 값·도달 시각(UTC)과 시퀀스 revision을 한 스냅샷으로 저장한다")
    void publishCancelled_challenge_includesRevision() throws Exception {
        var routine = routine();
        ReflectionTestUtils.setField(routine, "challengeId", 5L);
        when(repository.nextRevision()).thenReturn(4821L);
        publisher().publishCancelled(routine, 100L, LocalDate.of(2026, 9, 20),
                new AcceptedCount(12, Instant.parse("2026-09-19T00:00:00Z")));
        var outbox = saved();
        var json = mapper.readTree(outbox.getPayload());
        assertThat(json.path("acceptedCount").asInt()).isEqualTo(12);
        assertThat(json.path("reachedAt").asText()).isEqualTo("2026-09-19T00:00:00Z");
        assertThat(json.path("revision").asLong()).isEqualTo(4821L);
        assertThat(outbox.getEventType()).isEqualTo("routine.execution.cancelled");
        assertThat(outbox.getIdempotencyKey()).isEqualTo("ROUTINE_EXECUTION:100:cancelled:4821");
    }

    @Test @DisplayName("챌린지 인정 횟수가 0이면 도달 시각을 null로 명시한다")
    void publishCancelled_challengeZero_emitsNullReachedAt() throws Exception {
        var routine = routine();
        ReflectionTestUtils.setField(routine, "challengeId", 5L);
        when(repository.nextRevision()).thenReturn(4830L);
        publisher().publishCancelled(routine, 100L, LocalDate.of(2026, 9, 20), AcceptedCount.ZERO);
        var json = mapper.readTree(saved().getPayload());
        assertThat(json.path("acceptedCount").asInt()).isZero();
        assertThat(json.has("reachedAt")).isTrue();
        assertThat(json.path("reachedAt").isNull()).isTrue();
    }

    @Test @DisplayName("중단과 선호 시각 해제도 revision을 붙인 알림 스냅샷을 저장한다")
    void publishNotificationScheduled_inactive_emitsSnapshot() throws Exception {
        var routine = routine();
        routine.changePreferences(null, (short) 69);
        routine.deactivate();
        when(repository.nextRevision()).thenReturn(4822L);
        publisher().publishNotificationScheduled(routine);
        var outbox = saved();
        var json = mapper.readTree(outbox.getPayload());
        assertThat(json.path("active").asBoolean()).isFalse();
        assertThat(json.has("preferredTime")).isTrue();
        assertThat(json.path("preferredTime").isNull()).isTrue();
        assertThat(json.path("preferredDays").toString()).isEqualTo("[\"MON\",\"WED\",\"SUN\"]");
        assertThat(json.path("revision").asLong()).isEqualTo(4822L);
        assertThat(outbox.getCreatedAt()).isEqualTo(LocalDateTime.now(clock));
    }

    @Test @DisplayName("요일 미설정은 빈 배열이 아니라 null로 싣는다")
    void publishNotificationScheduled_noDays_emitsNull() throws Exception {
        publisher().publishNotificationScheduled(routine());
        var json = mapper.readTree(saved().getPayload());
        assertThat(json.has("daysOfWeek")).isTrue();
        assertThat(json.path("daysOfWeek").isNull()).isTrue();
        assertThat(json.path("preferredDays").isNull()).isTrue();
    }
}
