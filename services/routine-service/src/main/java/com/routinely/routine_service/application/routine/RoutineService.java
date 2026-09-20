package com.routinely.routine_service.application.routine;
import com.routinely.routine_service.application.routine.dto.*;
import com.routinely.routine_service.domain.execution.ExecutionStatus;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
public interface RoutineService {
    RoutineResult start(StartRoutineCommand command);
    List<RoutineResult> getMyRoutines(Long userId, Boolean isActive, Long challengeId);
    void stop(Long routineId, Long userId);
    RoutineResult update(Long routineId, Long userId, UpdateRoutineCommand command);
}
