package com.routinely.routine_service.application.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.routinely.core.constant.KafkaTopics;
import com.routinely.routine_service.application.execution.AcceptedCount;
import com.routinely.routine_service.domain.outbox.RoutineOutbox;
import com.routinely.routine_service.domain.outbox.RoutineOutboxRepository;
import com.routinely.routine_service.domain.routine.Routine;
import com.routinely.routine_service.domain.template.Weekdays;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class RoutineEventPublisherImpl implements RoutineEventPublisher {
    private final RoutineOutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void publishCompleted(Routine routine, Long executionId, LocalDate execDate, AcceptedCount ranking) {
        publishExecution(routine, executionId, execDate, ranking, false);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void publishCancelled(Routine routine, Long executionId, LocalDate execDate, AcceptedCount ranking) {
        publishExecution(routine, executionId, execDate, ranking, true);
    }

    private void publishExecution(Routine routine, Long executionId, LocalDate execDate,
                                  AcceptedCount ranking, boolean cancelled) {
        Map<String, Object> payload = envelope(routine);
        payload.put("executionId", executionId);
        payload.put("execDate", execDate.toString());
        payload.put("challengeId", routine.getChallengeId());
        Long revision = null;
        if (routine.isChallengeRoutine()) {
            // 세 필드가 한 벌의 완전한 스냅샷이다 — 소비자는 이전 이벤트 없이 이것만으로 덮어쓴다.
            revision = outboxRepository.nextRevision();
            payload.put("acceptedCount", ranking.count());
            payload.put("reachedAt", ranking.reachedAt() == null ? null : ranking.reachedAt().toString());
            payload.put("revision", revision);
        }
        String key = "ROUTINE_EXECUTION:" + executionId + (cancelled ? ":cancelled:" +
                (revision == null ? payload.get("eventId") : revision) : ":completed");
        save("ROUTINE_EXECUTION", executionId, routine.getUserId(),
                cancelled ? KafkaTopics.ROUTINE_EXECUTION_CANCELLED : KafkaTopics.ROUTINE_EXECUTION_COMPLETED,
                payload, key);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void publishNotificationScheduled(Routine routine) {
        Map<String, Object> payload = envelope(routine);
        // 같은 루틴의 수정·중단은 루틴 행 잠금 안에서 채번하므로 revision 순서 = 커밋 순서다.
        // 소비자는 occurredAt(인스턴스 간 시계 차이에 약함) 대신 이 값으로 늦게 온 옛 스냅샷을 버린다.
        payload.put("revision", outboxRepository.nextRevision());
        var definition = routine.getDefinition();
        payload.put("title", definition.getTitle());
        payload.put("active", routine.isActive());
        payload.put("scheduleType", definition.getScheduleType().name());
        payload.put("daysOfWeek", days(definition.getDaysOfWeek()));
        payload.put("targetCount", definition.getTargetCount());
        payload.put("preferredTime", routine.getPreferredTime() == null ? null :
                routine.getPreferredTime().format(DateTimeFormatter.ofPattern("HH:mm")));
        payload.put("preferredDays", days(routine.getPreferredDays()));
        payload.put("startedAt", routine.getStartedAt().toString());
        payload.put("endedAt", routine.getEndedAt() == null ? null : routine.getEndedAt().toString());
        save("ROUTINE", routine.getId(), routine.getUserId(), KafkaTopics.ROUTINE_NOTIFICATION_SCHEDULED,
                payload, "ROUTINE:" + routine.getId() + ":notification.scheduled:" + payload.get("eventId"));
    }

    private Map<String, Object> envelope(Routine routine) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventId", UUID.randomUUID().toString());
        payload.put("occurredAt", clock.instant().toString());
        payload.put("userId", routine.getUserId());
        payload.put("routineId", routine.getId());
        return payload;
    }

    /** 미설정(NULL)은 빈 배열이 아니라 null로 싣는다 — 계약상 두 값의 의미가 다르다. */
    private List<String> days(Short mask) {
        return mask == null ? null : Weekdays.toCodes(mask);
    }

    private void save(String aggregateType, Long aggregateId, Long userId, String topic,
                      Map<String, Object> payload, String key) {
        try {
            outboxRepository.save(RoutineOutbox.create(aggregateType, aggregateId, topic,
                    objectMapper.writeValueAsString(payload), key, userId.toString(),
                    LocalDateTime.now(clock)));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("루틴 이벤트 직렬화에 실패했습니다.", e);
        }
    }
}
