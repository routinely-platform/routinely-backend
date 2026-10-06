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

import java.time.LocalDateTime;

/** 캡 적용 누적 인정 횟수와 revision을 저장하는 랭킹 read 모델. */
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

    @Column(name = "accepted_count", nullable = false)
    private int acceptedCount;

    @Column(nullable = false)
    private long revision;

    @Column(name = "last_completed_at")
    private LocalDateTime lastCompletedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /** 멤버 참여 시점의 인정 횟수 0회, revision 0인 집계 행. */
    public static ChallengeMemberSummary create(Long challengeId, Long userId) {
        ChallengeMemberSummary summary = new ChallengeMemberSummary();
        summary.challengeId = challengeId;
        summary.userId = userId;

        return summary;
    }

    /**
     * routine-service가 보낸 랭킹 스냅샷으로 덮어쓴다. revision이 저장값보다 클 때만 반영한다.
     *
     * <p>{@code lastCompletedAt}은 동점 나열 기준인 "현재 인정 횟수에 도달한 시각"(ADR-0043)이며
     * <b>발행 측이 계산해 싣는다.</b> 여기서 이전 값과 비교해 추론하면 이벤트 처리 순서에 따라 결과가
     * 달라진다(역순으로 받으면 캡 초과 완료의 시각이 남는다). 0회면 null로 덮어쓴다.
     */
    public void applyAcceptedCount(int acceptedCount, long revision, LocalDateTime reachedAt) {
        if (revision <= this.revision) return;
        this.acceptedCount = acceptedCount;
        this.lastCompletedAt = reachedAt;
        this.revision = revision;
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
