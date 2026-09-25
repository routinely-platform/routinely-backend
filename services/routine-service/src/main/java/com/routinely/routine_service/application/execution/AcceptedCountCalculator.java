package com.routinely.routine_service.application.execution;

import com.routinely.routine_service.domain.execution.CompletionMark;
import com.routinely.routine_service.domain.execution.RoutineExecutionRepository;
import com.routinely.routine_service.domain.routine.Routine;
import com.routinely.routine_service.domain.schedule.WeekBoundary;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 챌린지 랭킹 스냅샷 계산 — 캡 적용 누적 인정 횟수와 도달 시각. (#61, ADR-0043)
 *
 * <ul>
 *     <li>주·월 묶음은 수행 날짜({@code scheduledDate}) 기준 — 백필도 그 날짜가 속한 주/월로 센다</li>
 *     <li>인정할 기록은 완료 처리 시각({@code completedAt}) 오름차순으로 고른다 — 묶음마다 목표 N개까지</li>
 *     <li>DAILY·SPECIFIC_DAYS는 모든 완료를 인정한다</li>
 *     <li>시작/종료의 불완전한 주·월도 목표를 줄이지 않는다</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class AcceptedCountCalculator {
    private final RoutineExecutionRepository executionRepository;
    private final Clock clock;

    public AcceptedCount calculate(Routine routine) {
        List<CompletionMark> marks = executionRepository.findCompletionMarks(
                routine.getId(), routine.getStartedAt(), routine.getEndedAt());
        var definition = routine.getDefinition();
        Function<LocalDate, Object> bucketOf = switch (definition.getScheduleType()) {
            case WEEKLY_COUNT -> WeekBoundary::startOfWeek;
            case MONTHLY_COUNT -> YearMonth::from;
            default -> null;
        };

        Map<Object, Integer> perBucket = new HashMap<>();
        int count = 0;
        LocalDateTime reachedAt = null;
        for (CompletionMark mark : marks) {
            if (bucketOf != null
                    && perBucket.merge(bucketOf.apply(mark.scheduledDate()), 1, Integer::sum) > definition.getTargetCount()) {
                continue; // 캡 초과 — 인정하지 않으므로 도달 시각도 옮기지 않는다
            }
            count++;
            reachedAt = mark.completedAt();
        }
        // completed_at은 LocalDateTime.now(clock)으로 기록한 서비스 시간대 벽시계다. 오프셋 없이 내보내지 않는다.
        return count == 0 ? AcceptedCount.ZERO
                : new AcceptedCount(count, reachedAt.atZone(clock.getZone()).toInstant());
    }
}
