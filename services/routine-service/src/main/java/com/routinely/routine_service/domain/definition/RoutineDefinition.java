package com.routinely.routine_service.domain.definition;

import com.routinely.core.exception.BusinessException;
import com.routinely.routine_service.domain.template.ScheduleType;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import static com.routinely.core.exception.ErrorCode.VALIDATION_FAILED;

/**
 * 루틴 정의 — 무엇을, 얼마나. (ADR-0039 반복 스케줄, ADR-0040 인스턴스 독립)
 *
 * <p><b>템플릿과 루틴이 같은 다섯 값을 갖는다.</b> 템플릿은 루틴을 만드는 도구이고,
 * 루틴은 시작 시점에 이 값을 <b>복사</b>해 자기완결적으로 존재한다. 그래서 상속이 아니라
 * 값 객체다 — 루틴은 템플릿의 일종<i>이 아니라</i> 정의를 <b>가진다</b>.
 *
 * <p><b>이 클래스가 {@code ck_rt_schedule} / {@code ck_routines_schedule} 두 CHECK 제약의
 * 유일한 코드 대응물이다.</b> ADR-0040이 "두 제약은 짝이고 컴파일러가 잡지 못하는 결합"이라고
 * 경고했는데, 정의를 여기로 모아 <b>잘못된 조합으로는 객체를 만들 수 없게</b> 했다.
 * 제약이 바뀌면 마이그레이션과 이 클래스만 함께 고친다.
 *
 * <p>불변이다. 값을 바꾸는 대신 새 인스턴스를 만든다({@code withTitle}, {@code withSchedule}).
 * 그래야 어떤 경로로 바꾸든 검증을 지나간다.
 *
 * <p>⚠️ {@code daysOfWeek}(정의)와 {@code Routine.preferredDays}(알림용 soft 선호)는
 * 같은 비트마스크 타입이지만 전혀 다른 것이다. 전자는 완료를 제약하고 후자는 제약하지 않는다.
 */
@Embeddable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RoutineDefinition {

    @Column(name = "title", nullable = false, length = 100)
    private String title;

    @Column(name = "category_code", nullable = false, length = 30)
    private String categoryCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "schedule_type", nullable = false, length = 20)
    private ScheduleType scheduleType;

    /** 지정 요일 비트마스크(bit0=월 … bit6=일). SPECIFIC_DAYS 전용, 그 외 NULL. */
    @Column(name = "days_of_week")
    private Short daysOfWeek;

    /** 기간당 목표 횟수. WEEKLY_COUNT/MONTHLY_COUNT 전용, 그 외 NULL. */
    @Column(name = "target_count")
    private Integer targetCount;

    /**
     * 정의를 만든다. <b>스케줄 정합성을 통과하지 못하면 객체가 만들어지지 않는다.</b>
     *
     * @throws BusinessException 유형과 days_of_week/target_count 조합이 어긋날 때
     */
    public static RoutineDefinition of(String title, String categoryCode, ScheduleType scheduleType,
                                       Short daysOfWeek, Integer targetCount) {
        validateSchedule(scheduleType, daysOfWeek, targetCount);

        RoutineDefinition definition = new RoutineDefinition();
        definition.title = title;
        definition.categoryCode = categoryCode;
        definition.scheduleType = scheduleType;
        definition.daysOfWeek = daysOfWeek;
        definition.targetCount = targetCount;
        return definition;
    }

    /**
     * 루틴 시작 시 정의를 복사한다 (ADR-0040 §2.1).
     *
     * <p>정의 필드가 나중에 늘어도 호출부를 고칠 필요가 없다 — 그게 이 메서드의 목적이다.
     */
    public RoutineDefinition copy() {
        return of(title, categoryCode, scheduleType, daysOfWeek, targetCount);
    }

    public RoutineDefinition withTitle(String newTitle) {
        return of(newTitle, categoryCode, scheduleType, daysOfWeek, targetCount);
    }

    public RoutineDefinition withCategoryCode(String newCategoryCode) {
        return of(title, newCategoryCode, scheduleType, daysOfWeek, targetCount);
    }

    /**
     * 반복 스케줄을 바꾼다. 유형에 따라 사용하는 컬럼이 달라 <b>세 값을 항상 함께</b> 넘긴다.
     */
    public RoutineDefinition withSchedule(ScheduleType newType, Short newDaysOfWeek, Integer newTargetCount) {
        return of(title, categoryCode, newType, newDaysOfWeek, newTargetCount);
    }

    /**
     * 스케줄 유형별 필드 정합성 — {@code ck_rt_schedule} / {@code ck_routines_schedule} 미러링.
     *
     * <p>{@code daysOfWeek == 0}도 "제공됨"으로 본다. DB에서 0은 NULL이 아니어서
     * DAILY·빈도형 CHECK에 위배되고, SPECIFIC_DAYS에서도 1~127 범위 밖이다.
     */
    private static void validateSchedule(ScheduleType scheduleType, Short daysOfWeek, Integer targetCount) {
        if (scheduleType == null) {
            throw new BusinessException(VALIDATION_FAILED, "반복 유형은 필수입니다.");
        }
        boolean hasDays = daysOfWeek != null;
        boolean hasCount = targetCount != null;

        if (scheduleType.requiresDaysOfWeek()) {
            if (!hasDays || hasCount) {
                throw new BusinessException(VALIDATION_FAILED,
                        "SPECIFIC_DAYS는 요일 지정이 필요하며 목표 횟수를 가질 수 없습니다.");
            }
            if (daysOfWeek < 1 || daysOfWeek > 127) {
                throw new BusinessException(VALIDATION_FAILED,
                        "요일 지정은 월~일(비트마스크 1~127) 범위여야 합니다.");
            }
        } else if (scheduleType.requiresTargetCount()) {
            if (!hasCount || targetCount < 1 || targetCount > (scheduleType == ScheduleType.WEEKLY_COUNT ? 6 : 28) || hasDays) {
                throw new BusinessException(VALIDATION_FAILED,
                        "WEEKLY_COUNT/MONTHLY_COUNT는 각각 1~6회/1~28회의 목표 횟수가 필요하며 요일을 가질 수 없습니다.");
            }
        } else if (hasDays || hasCount) {
            throw new BusinessException(VALIDATION_FAILED, "DAILY는 요일·목표 횟수를 가질 수 없습니다.");
        }
    }
}
