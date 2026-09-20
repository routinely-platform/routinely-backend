package com.routinely.routine_service.application.execution;
import com.routinely.routine_service.application.execution.dto.*;
import com.routinely.routine_service.domain.execution.ExecutionStatus;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
public interface RoutineExecutionService {
    ExecutionCompleteResult complete(CompleteExecutionCommand command);
    ExecutionCompleteResult cancelComplete(Long routineId, Long userId, LocalDate date);
    List<ExecutionResult> getMyExecutions(Long userId, Long routineId, ExecutionStatus status, LocalDate startDate, LocalDate endDate);
    List<ExecutionCompleteResult> bulkComplete(Long userId, LocalDate date, List<Long> routineIds);
}
