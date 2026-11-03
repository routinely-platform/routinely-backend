package com.routinely.routine_service.domain.template;

import com.routinely.core.schedule.ScheduleType;
import com.routinely.jpa.entity.BaseEntity;
import com.routinely.routine_service.domain.definition.RoutineDefinition;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 루틴 템플릿 — 루틴의 틀(무엇을/얼마나)을 정의한다.
 *
 * <p>반복 스케줄은 유형(scheduleType)에 따라 사용하는 컬럼이 다르다 (ADR-0039).
 * <ul>
 *   <li>{@code DAILY} — days_of_week/target_count 모두 NULL</li>
 *   <li>{@code SPECIFIC_DAYS} — days_of_week(비트마스크) 필수, target_count NULL</li>
 *   <li>{@code WEEKLY_COUNT / MONTHLY_COUNT} — target_count 필수, days_of_week NULL</li>
 * </ul>
 * {@code ck_rt_schedule} CHECK 제약과의 정합은 {@link RoutineDefinition}이 보장한다 —
 * 잘못된 조합으로는 정의 객체 자체가 만들어지지 않는다.
 *
 * <p>사용자가 저장한 틀 전용이다. 챌린지 루틴 정의는 challenge-service가 소유하고 템플릿을 만들지 않는다. (ADR-0044)
 *
 * <p>선호 시각·선호 요일은 템플릿이 아닌 멤버별 routines 인스턴스에서 개별 설정한다. (ADR-0035)
 *
 * <p>{@code created_at} / {@code updated_at} 은 {@link BaseEntity}가 JPA Auditing으로 관리한다.
 */
@Entity
@Table(name = "routine_templates")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RoutineTemplate extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 무엇을·얼마나 — 루틴 시작 시 인스턴스로 복사된다 (ADR-0040). */
    @Embedded
    private RoutineDefinition definition;

    @Column(name = "is_deleted", nullable = false)
    private boolean isDeleted;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    /**
     * 개인 루틴 템플릿을 생성한다. 템플릿은 사용자가 저장한 틀 전용이다 —
     * 챌린지 루틴 정의는 challenge-service가 소유한다(ADR-0044).
     */
    public static RoutineTemplate forPersonal(Long userId, RoutineDefinition definition) {
        RoutineTemplate template = new RoutineTemplate();
        template.userId = userId;
        template.definition = definition;
        template.isDeleted = false;
        return template;
    }

    public void changeTitle(String title) {
        this.definition = definition.withTitle(title);
    }

    public void changeCategoryCode(String categoryCode) {
        this.definition = definition.withCategoryCode(categoryCode);
    }

    /**
     * 반복 스케줄을 변경한다. 유형에 따라 days_of_week/target_count 사용 여부가 달라
     * 항상 세 값을 함께 넘기며, 정합성은 {@link RoutineDefinition}이 검증한다.
     */
    public void changeSchedule(ScheduleType scheduleType, Short daysOfWeek, Integer targetCount) {
        this.definition = definition.withSchedule(scheduleType, daysOfWeek, targetCount);
    }

    /**
     * 소프트 삭제 — 물리 삭제 대신 삭제 플래그와 삭제 시각만 기록한다.
     */
    public void softDelete(LocalDateTime deletedAt) {
        this.isDeleted = true;
        this.deletedAt = deletedAt;
    }
}
