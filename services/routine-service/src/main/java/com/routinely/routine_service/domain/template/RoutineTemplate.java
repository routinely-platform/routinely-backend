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
 * 루틴 템플릿 — 루틴의 틀을 정의한다.
 *
 * <p>챌린지 연결 템플릿은 challenge.created 이벤트를 소비해 생성하며 challenge_id를 갖는다.
 * {@code uq_rt_challenge_id} UNIQUE 제약으로 챌린지당 1개만 보장된다. (ADR-0034)
 *
 * <p>선호 시각(preferred_time)은 템플릿이 아닌 멤버별 routines 인스턴스에서 개별 설정하므로
 * 템플릿에는 존재하지 않는다. (ADR-0035)
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
    @Column(name = "repeat_type", nullable = false, length = 20)
    private RepeatType repeatType;

    @Column(name = "repeat_value")
    private Integer repeatValue;

    @Column(name = "is_deleted", nullable = false)
    private boolean isDeleted;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    /**
     * 챌린지 연결 루틴 템플릿을 생성한다.
     *
     * @param userId       템플릿 소유자(챌린지 생성자) ID — 항상 존재
     * @param challengeId  연결된 챌린지 ID — UNIQUE
     * @param repeatValue  DAILY_N/WEEKLY_N/MONTHLY_N이면 필수, 그 외 NULL (CHECK 제약과 정합)
     */
    public static RoutineTemplate forChallenge(Long userId, Long challengeId, String title,
                                               String categoryCode, RepeatType repeatType,
                                               Integer repeatValue) {
        RoutineTemplate template = new RoutineTemplate();
        template.userId = userId;
        template.challengeId = challengeId;
        template.title = title;
        template.categoryCode = categoryCode;
        template.repeatType = repeatType;
        template.repeatValue = repeatValue;
        template.isDeleted = false;
        return template;
    }
}
