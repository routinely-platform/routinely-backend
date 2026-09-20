package com.routinely.routine_service.domain.routine;

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

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * 루틴 인스턴스 — 사용자에게 활성화된 루틴. 루틴 템플릿(정의)을 기반으로 생성된다.
 *
 * <p><b>루틴은 자기 정의를 갖는다 (ADR-0040).</b> 시작 시점에 템플릿에서 복사해 오므로 이후 템플릿을
 * 고치거나 지워도 흔들리지 않는다. {@code routine_template_id}는 <b>출처 표시일 뿐</b>이고 NULL일 수
 * 있으며(템플릿 없이 직접 시작), 조회·판정에 사용하지 않는다.
 *
 * <p>개인 루틴은 {@code POST /api/v1/routines}로 사용자가 직접 시작하며 challengeId는 NULL이다.
 * 챌린지 루틴은 {@code challenge.started} 이벤트 소비로 자동 생성되며(ADR-0032, 별도 구현) challengeId를 갖는다.
 *
 * <p>선호 수행 시각(preferred_time)은 알림 발송 기준이며 인스턴스가 소유한다. NULL이면 리마인더를
 * 발송하지 않는다. 설정/수정은 {@code PATCH /api/v1/routines/{routineId}}에서 처리한다 (#139, ADR-0035).
 *
 * <p>{@code created_at} / {@code updated_at} 은 {@link BaseEntity}가 JPA Auditing으로 관리한다.
 */
@Entity
@Table(name = "routines")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Routine extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 출처 템플릿 ID — 선택. 조회·판정에 쓰지 않는다 (ADR-0040 §2.4). */
    @Column(name = "routine_template_id")
    private Long routineTemplateId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "challenge_id")
    private Long challengeId;

    /** 시작 시점에 복사한 정의 — 무엇을·얼마나. 완료 기록이 생긴 뒤에는 스케줄을 바꿀 수 없다. */
    @Embedded
    private RoutineDefinition definition;

    @Column(name = "started_at", nullable = false)
    private LocalDate startedAt;

    /** 종료일 — NULL이면 무기한(개인 루틴만). 챌린지 루틴은 생성 경로에서 NOT NULL을 보장한다 (ADR-0041). */
    @Column(name = "ended_at")
    private LocalDate endedAt;

    @Column(name = "preferred_time")
    private LocalTime preferredTime;

    /** 선호 요일 비트마스크(bit0=월 … bit6=일). 빈도 유형 루틴의 알림/표시용 선호(soft), NULL 허용. (ADR-0039) */
    @Column(name = "preferred_days")
    private Short preferredDays;

    @Column(name = "is_active", nullable = false)
    private boolean isActive;

    /**
     * 개인 루틴 인스턴스를 생성한다. 챌린지와 연결되지 않으므로 challengeId는 NULL이다.
     *
     * @param routineTemplateId 출처 템플릿 ID — 선택(직접 만든 루틴이면 NULL)
     * @param definition        시작 시점의 정의 사본 — 템플릿에서 왔다면 {@code copy()}로 넘긴다
     * @param endedAt           종료일 — 선택(NULL이면 무기한)
     * @param preferredTime     알림 발송 기준 시각 — 선택(NULL이면 리마인더 미발송)
     */
    public static Routine forPersonal(Long routineTemplateId, Long userId, RoutineDefinition definition,
                                      LocalDate startedAt, LocalDate endedAt, LocalTime preferredTime) {
        Routine routine = new Routine();
        routine.routineTemplateId = routineTemplateId;
        routine.userId = userId;
        routine.definition = definition;
        routine.startedAt = startedAt;
        routine.endedAt = endedAt;
        routine.preferredTime = preferredTime;
        routine.isActive = true;
        return routine;
    }

    /** 챌린지 루틴 여부 — 중단·정의 수정이 막힌다 (ADR-0036). */
    public boolean isChallengeRoutine() {
        return challengeId != null;
    }

    /** 무기한 루틴 여부 — 파생 범위와 완료 가능 기간 검증이 갈린다 (ADR-0041). */
    public boolean hasNoEndDate() {
        return endedAt == null;
    }

    /**
     * 루틴 중단 — 물리 삭제 대신 활성 플래그만 내린다. 이미 중단된 루틴이면 멱등하게 유지된다.
     */
    public void changeDefinition(RoutineDefinition definition) {
        this.definition = definition;
    }

    public void changePeriod(LocalDate startedAt, LocalDate endedAt) {
        this.startedAt = startedAt;
        this.endedAt = endedAt;
    }

    public void deactivate() {
        this.isActive = false;
    }

    /**
     * 선호 수행 시각·선호 요일(알림/표시 기준)을 함께 설정/변경한다. 각 값이 NULL이면 해당 설정을 해제한다.
     * 요일은 완료를 제약하지 않는 soft 선호다. (ADR-0035, ADR-0039, #139)
     */
    public void changePreferences(LocalTime preferredTime, Short preferredDays) {
        this.preferredTime = preferredTime;
        this.preferredDays = preferredDays;
    }
}
