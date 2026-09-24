package com.routinely.routine_service.application.event;

import com.routinely.routine_service.domain.routine.Routine;
import java.time.LocalDate;

public interface RoutineEventPublisher {
    void publishCompleted(Routine routine, Long executionId, LocalDate execDate, Integer acceptedCount);
    void publishCancelled(Routine routine, Long executionId, LocalDate execDate, Integer acceptedCount);
    void publishNotificationScheduled(Routine routine);
}
