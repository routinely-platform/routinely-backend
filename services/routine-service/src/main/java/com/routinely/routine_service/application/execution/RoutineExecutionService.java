package com.routinely.routine_service.application.execution;
import com.routinely.routine_service.application.execution.dto.*;
import com.routinely.routine_service.domain.execution.ExecutionStatus;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
public interface RoutineExecutionService {
    /** 챌린지 확인을 마친 뒤에만 부른다 — 진입점은 {@link RoutineExecutionCompletionService#complete}. */
    ExecutionCompleteResult completeAfterChallengeCheck(CompleteExecutionCommand command);
    ExecutionCompleteResult cancelComplete(Long routineId, Long userId, LocalDate date);
    List<ExecutionResult> getMyExecutions(Long userId, Long routineId, ExecutionStatus status, LocalDate startDate, LocalDate endDate);
    /** 챌린지 확인을 마친 뒤에만 부른다 — 진입점은 {@link RoutineExecutionCompletionService#bulkComplete}. */
    List<ExecutionCompleteResult> bulkCompleteAfterChallengeCheck(Long userId, LocalDate date, List<Long> routineIds);
}
