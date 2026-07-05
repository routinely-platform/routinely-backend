package com.routinely.challenge_service.domain.summary;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 챌린지 멤버별 달성 집계 — 랭킹 조회 read 최적화용 read 모델. (ADR-0028 이벤트 기반 집계)
 *
 * <p>달성률(achievement_rate)이 랭킹 점수의 단일 출처이며, Redis ZSET의 fallback 저장소이기도 하다.
 * 캡 적용 달성률 계산(ADR-0027)은 반복 규칙을 소유한 routine-service가 수행하고,
 * challenge-service는 {@code routine.execution.completed} 이벤트가 실어 보낸 결과값을 저장만 한다.
 *
 * <p>멤버 참여 시점에는 {@link #create}로 0% 행을 만들고(랭킹에 0%로 노출),
 * 루틴 완료 이벤트 처리 시 {@link #applyCompletion}으로 집계값을 갱신한다.
 */
@Entity
@Table(name = "challenge_member_summary")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChallengeMemberSummary {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "challenge_id", nullable = false)
    private Long challengeId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "total_scheduled", nullable = false)
    private int totalScheduled;

    @Column(name = "completed_count", nullable = false)
    private int completedCount;

    @Column(name = "achievement_rate", nullable = false, precision = 5, scale = 2)
    private BigDecimal achievementRate;

    @Column(name = "last_completed_at")
    private LocalDateTime lastCompletedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /**
     * 멤버 참여 시점의 0% 집계 행. total_scheduled는 완료 이벤트 수신 전까지 0으로 둔다
     * (ck_cms_counts: completed_count(0) <= total_scheduled(0) 충족).
     */
    public static ChallengeMemberSummary create(Long challengeId, Long userId) {
        ChallengeMemberSummary summary = new ChallengeMemberSummary();
        summary.challengeId = challengeId;
        summary.userId = userId;
        summary.totalScheduled = 0;
        summary.completedCount = 0;
        summary.achievementRate = BigDecimal.ZERO;
        return summary;
    }

    /**
     * 루틴 완료 이벤트가 전달한 집계값으로 갱신한다. 값은 routine-service에서 캡까지 적용해
     * 계산한 결과이므로 여기서는 그대로 반영한다. (멱등 — 같은 최종값으로 덮어써도 결과 동일)
     */
    public void applyCompletion(int totalScheduled, int completedCount,
                                BigDecimal achievementRate, LocalDateTime lastCompletedAt) {
        this.totalScheduled = totalScheduled;
        this.completedCount = completedCount;
        this.achievementRate = achievementRate;
        this.lastCompletedAt = lastCompletedAt;
    }

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
