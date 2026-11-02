package com.routinely.challenge_service.domain.challenge;

import static com.routinely.core.exception.ErrorCode.VALIDATION_FAILED;

import com.routinely.core.exception.BusinessException;
import com.routinely.core.schedule.ScheduleType;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 챌린지 루틴 정의 — 멤버 모두가 같은 루틴을 수행한다. (ADR-0044)
 *
 * <p>챌린지가 루틴 정의를 <b>직접 소유</b>한다. 시작 시 {@code challenge.started} 페이로드에 실려
 * routine-service가 멤버별 루틴을 만든다. 시작 전에 참여하는 것이 사전 동의이므로 요일 지정 ({@code SPECIFIC_DAYS})도 허용한다(ADR-0039 §2.3, 2026-08-29
 * 개정).
 *
 * <p><b>이 클래스가 {@code ck_challenges_schedule} CHECK 제약의 코드 대응물이다.</b> 같은 규칙이
 * routine-service의 {@code ck_rt_schedule} · {@code ck_routines_schedule} · {@code RoutineDefinition}에도 있다. 세 제약은 짝이라
 * 한쪽을 고치면 넷을 함께 고친다.
 *
 * <p>카테고리는 두지 않는다 — {@code challenges.category_code}가 루틴 카테고리를 겸한다.
 *
 * <p>불변이다. 제목을 바꿀 때도 새 인스턴스를 만들어 검증을 다시 지나간다.
 */
@Embeddable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChallengeRoutineDefinition {

    @Column(name = "routine_title", nullable = false, length = 100)
    private String routineTitle;

    @Enumerated(EnumType.STRING)
    @Column(name = "schedule_type", nullable = false, length = 20)
    private ScheduleType scheduleType;

    /**
     * 지정 요일 비트마스크(bit0=월 … bit6=일). SPECIFIC_DAYS 전용, 그 외 NULL.
     */
    @Column(name = "days_of_week")
    private Short daysOfWeek;

    /**
     * 기간당 목표 횟수. WEEKLY_COUNT(1~6)/MONTHLY_COUNT(1~28) 전용, 그 외 NULL.
     */
    @Column(name = "target_count")
    private Integer targetCount;

    /**
     * 정의를 만든다. <b>스케줄 정합성을 통과하지 못하면 객체가 만들어지지 않는다.</b>
     *
     * @throws BusinessException 유형과 days_of_week/target_count 조합이 어긋날 때
     */
    public static ChallengeRoutineDefinition of(String routineTitle, ScheduleType scheduleType,
                                                Short daysOfWeek, Integer targetCount) {
        validateSchedule(scheduleType, daysOfWeek, targetCount);

        ChallengeRoutineDefinition definition = new ChallengeRoutineDefinition();
        definition.routineTitle = routineTitle;
        definition.scheduleType = scheduleType;
        definition.daysOfWeek = daysOfWeek;
        definition.targetCount = targetCount;
        return definition;
    }

    public ChallengeRoutineDefinition withRoutineTitle(String newRoutineTitle) {
        return of(newRoutineTitle, scheduleType, daysOfWeek, targetCount);
    }

    /**
     * 스케줄 유형별 필드 정합성 — {@code ck_challenges_schedule} 미러링.
     */
    private static void validateSchedule(ScheduleType scheduleType, Short daysOfWeek, Integer targetCount) {
        if (scheduleType == null) {
            throw new BusinessException(VALIDATION_FAILED, "반복 유형은 필수입니다.");
        }
        if (scheduleType.requiresDaysOfWeek()) {
            validateSpecificDays(daysOfWeek, targetCount);
        } else if (scheduleType.requiresTargetCount()) {
            validateTargetCount(scheduleType, daysOfWeek, targetCount);
        } else if (daysOfWeek != null || targetCount != null) {
            throw new BusinessException(VALIDATION_FAILED, "DAILY는 요일·목표 횟수를 가질 수 없습니다.");
        }
    }

    private static void validateSpecificDays(Short daysOfWeek, Integer targetCount) {
        if (daysOfWeek == null || targetCount != null) {
            throw new BusinessException(VALIDATION_FAILED,
                    "SPECIFIC_DAYS는 요일 지정이 필요하며 목표 횟수를 가질 수 없습니다.");
        }
        if (daysOfWeek < 1 || daysOfWeek > 127) {
            throw new BusinessException(VALIDATION_FAILED,
                    "요일 지정은 월~일(비트마스크 1~127) 범위여야 합니다.");
        }
    }

    private static void validateTargetCount(ScheduleType scheduleType, Short daysOfWeek, Integer targetCount) {
        int maxCount = scheduleType == ScheduleType.WEEKLY_COUNT ? 6 : 28;
        if (targetCount == null || targetCount < 1 || targetCount > maxCount || daysOfWeek != null) {
            throw new BusinessException(VALIDATION_FAILED,
                    "WEEKLY_COUNT/MONTHLY_COUNT는 각각 1~6회/1~28회의 목표 횟수가 필요하며 요일을 가질 수 없습니다.");
        }
    }
}
