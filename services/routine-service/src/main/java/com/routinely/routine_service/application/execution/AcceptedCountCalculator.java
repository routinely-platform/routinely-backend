package com.routinely.routine_service.application.execution;

import com.routinely.routine_service.domain.execution.RoutineExecutionRepository;
import com.routinely.routine_service.domain.routine.Routine;
import com.routinely.routine_service.domain.schedule.WeekBoundary;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class AcceptedCountCalculator {
    private final RoutineExecutionRepository executionRepository;

    /** 발생 시각이 아니라 저장된 수행 날짜를 묶는다. 시작/종료의 불완전한 주도 목표를 줄이지 않는다. */
    public int calculate(Routine routine) {
        var dates = executionRepository.findCompletedDates(
                routine.getId(), routine.getStartedAt(), routine.getEndedAt());
        var definition = routine.getDefinition();
        Function<LocalDate, ?> bucket = switch (definition.getScheduleType()) {
            case WEEKLY_COUNT -> WeekBoundary::startOfWeek;
            case MONTHLY_COUNT -> YearMonth::from;
            default -> null;
        };
        if (bucket == null) return dates.size();
        return dates.stream().collect(Collectors.groupingBy(bucket, Collectors.counting()))
                .values().stream().mapToInt(count -> (int) Math.min(count, definition.getTargetCount())).sum();
    }
}
