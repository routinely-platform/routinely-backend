package com.routinely.routine_service.domain.routine;

import com.routinely.jpa.entity.BaseEntity;
import jakarta.persistence.Column;
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
 * <p>1 템플릿 : N 인스턴스 구조다. {@code routine_template_id}는 UNIQUE가 아니며, 한 개인 템플릿으로
 * 시기를 달리해 여러 번 시작할 수 있고, 챌린지 템플릿은 멤버 수만큼의 인스턴스가 참조한다. (ADR-0035)
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

    @Column(name = "routine_template_id", nullable = false)
    private Long routineTemplateId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "challenge_id")
    private Long challengeId;

    @Column(name = "started_at", nullable = false)
    private LocalDate startedAt;

    @Column(name = "ended_at", nullable = false)
    private LocalDate endedAt;

    @Column(name = "preferred_time")
    private LocalTime preferredTime;

    @Column(name = "is_active", nullable = false)
    private boolean isActive;

    /**
     * 개인 루틴 인스턴스를 생성한다. 챌린지와 연결되지 않으므로 challengeId는 NULL이다.
     *
     * @param preferredTime 알림 발송 기준 시각 — 선택(NULL이면 리마인더 미발송)
     */
    public static Routine forPersonal(Long routineTemplateId, Long userId, LocalDate startedAt,
                                      LocalDate endedAt, LocalTime preferredTime) {
        Routine routine = new Routine();
        routine.routineTemplateId = routineTemplateId;
        routine.userId = userId;
        routine.startedAt = startedAt;
        routine.endedAt = endedAt;
        routine.preferredTime = preferredTime;
        routine.isActive = true;
        return routine;
    }

    /**
     * 루틴 중단 — 물리 삭제 대신 활성 플래그만 내린다. 이미 중단된 루틴이면 멱등하게 유지된다.
     */
    public void deactivate() {
        this.isActive = false;
    }
}
