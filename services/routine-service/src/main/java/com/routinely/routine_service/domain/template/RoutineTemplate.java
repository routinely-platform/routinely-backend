package com.routinely.routine_service.domain.template;

import com.routinely.jpa.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
 * {@code ck_rt_schedule} CHECK 제약과 정합해야 하므로 스케줄은 항상 세 값을 한 번에 설정/변경한다.
 *
 * <p>챌린지 연결 템플릿은 challenge.created 이벤트를 소비해 생성하며 challenge_id를 갖는다.
 * {@code uq_rt_challenge_id} UNIQUE 제약으로 챌린지당 1개만 보장된다. (ADR-0034)
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

    @Column(name = "challenge_id", unique = true)
    private Long challengeId;

    @Column(nullable = false, length = 100)
    private String title;

    @Column(name = "category_code", nullable = false, length = 30)
    private String categoryCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "schedule_type", nullable = false, length = 20)
    private ScheduleType scheduleType;

    /** 특정 요일 비트마스크(bit0=월 … bit6=일). SPECIFIC_DAYS에서만 사용, 그 외 NULL. */
    @Column(name = "days_of_week")
    private Short daysOfWeek;

    /** 기간당 목표 횟수. WEEKLY_COUNT/MONTHLY_COUNT에서만 사용, 그 외 NULL. */
    @Column(name = "target_count")
    private Integer targetCount;

    @Column(name = "is_deleted", nullable = false)
    private boolean isDeleted;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    /**
     * 챌린지 연결 루틴 템플릿을 생성한다.
     *
     * @param userId       템플릿 소유자(챌린지 생성자) ID — 항상 존재
     * @param challengeId  연결된 챌린지 ID — UNIQUE
     */
    public static RoutineTemplate forChallenge(Long userId, Long challengeId, String title,
                                               String categoryCode, ScheduleType scheduleType,
                                               Short daysOfWeek, Integer targetCount) {
        RoutineTemplate template = new RoutineTemplate();
        template.userId = userId;
        template.challengeId = challengeId;
        template.title = title;
        template.categoryCode = categoryCode;
        template.scheduleType = scheduleType;
        template.daysOfWeek = daysOfWeek;
        template.targetCount = targetCount;
        template.isDeleted = false;
        return template;
    }

    /**
     * 개인 루틴 템플릿을 생성한다. 챌린지와 연결되지 않으므로 challengeId는 NULL이다.
     */
    public static RoutineTemplate forPersonal(Long userId, String title, String categoryCode,
                                              ScheduleType scheduleType, Short daysOfWeek,
                                              Integer targetCount) {
        RoutineTemplate template = new RoutineTemplate();
        template.userId = userId;
        template.title = title;
        template.categoryCode = categoryCode;
        template.scheduleType = scheduleType;
        template.daysOfWeek = daysOfWeek;
        template.targetCount = targetCount;
        template.isDeleted = false;
        return template;
    }

    public void changeTitle(String title) {
        this.title = title;
    }

    public void changeCategoryCode(String categoryCode) {
        this.categoryCode = categoryCode;
    }

    /**
     * 반복 스케줄을 변경한다. 유형에 따라 days_of_week/target_count 사용 여부가 달라
     * {@code ck_rt_schedule} 제약과 정합하도록 항상 세 값을 함께 설정한다.
     */
    public void changeSchedule(ScheduleType scheduleType, Short daysOfWeek, Integer targetCount) {
        this.scheduleType = scheduleType;
        this.daysOfWeek = daysOfWeek;
        this.targetCount = targetCount;
    }

    /**
     * 소프트 삭제 — 물리 삭제 대신 삭제 플래그와 삭제 시각만 기록한다.
     */
    public void softDelete(LocalDateTime deletedAt) {
        this.isDeleted = true;
        this.deletedAt = deletedAt;
    }

    /**
     * 챌린지 연결 템플릿 여부 — 연결 템플릿은 개인 CRUD API로 수정/삭제할 수 없다.
     */
    public boolean isChallengeLinked() {
        return challengeId != null;
    }
}
