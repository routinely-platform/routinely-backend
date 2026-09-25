package com.routinely.routine_service.application.event;

import com.routinely.routine_service.application.execution.AcceptedCount;
import com.routinely.routine_service.domain.routine.Routine;
import java.time.LocalDate;

public interface RoutineEventPublisher {
    void publishCompleted(Routine routine, Long executionId, LocalDate execDate, AcceptedCount ranking);
    void publishCancelled(Routine routine, Long executionId, LocalDate execDate, AcceptedCount ranking);
    void publishNotificationScheduled(Routine routine);
}
