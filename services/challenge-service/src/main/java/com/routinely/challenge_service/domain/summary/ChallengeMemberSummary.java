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
     * 루틴 완료 이벤트가 전달한 집계값으로 갱신한다. 값은 routine-service에서 캡까지 적용해
     * 계산한 결과이므로 여기서는 그대로 반영한다. (멱등 — 같은 최종값으로 덮어써도 결과 동일)
     *
     * <p>{@code lastCompletedAt}은 동점 나열 기준인 "현재 횟수에 도달한 시각"이다(ADR-0043).
     * 그래서 <b>인정 횟수가 늘었을 때만</b> 옮긴다. 캡을 넘긴 완료나 취소처럼 횟수가 그대로거나 줄어든
     * 이벤트로 시각을 늦추면, 더 수행한 사람이 동점 나열에서 뒤로 밀린다.
     */
    public void applyAcceptedCount(int acceptedCount, long revision, LocalDateTime occurredAt) {
        if (revision <= this.revision) return;
        if (acceptedCount > this.acceptedCount) {
            this.lastCompletedAt = occurredAt;
        }
        this.acceptedCount = acceptedCount;
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
