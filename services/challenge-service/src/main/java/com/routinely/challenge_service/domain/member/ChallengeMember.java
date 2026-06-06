package com.routinely.challenge_service.domain.member;

import com.routinely.challenge_service.domain.challenge.Challenge;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "challenge_members")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class ChallengeMember {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "challenge_id", nullable = false)
    private Challenge challenge;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ChallengeMemberRole role;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MembershipStatus status;

    @Column(name = "joined_at", nullable = false)
    private LocalDateTime joinedAt;

    @Column(name = "left_at")
    private LocalDateTime leftAt;

    public static ChallengeMember createLeader(Challenge challenge, Long userId, LocalDateTime now) {
        return ChallengeMember.builder()
                .challenge(challenge)
                .userId(userId)
                .role(ChallengeMemberRole.LEADER)
                .status(MembershipStatus.ACTIVE)
                .joinedAt(now)
                .build();
    }

    public static ChallengeMember createMember(Challenge challenge, Long userId, LocalDateTime now) {
        return ChallengeMember.builder()
                .challenge(challenge)
                .userId(userId)
                .role(ChallengeMemberRole.MEMBER)
                .status(MembershipStatus.ACTIVE)
                .joinedAt(now)
                .build();
    }

    public void rejoin(LocalDateTime now) {
        this.status = MembershipStatus.ACTIVE;
        this.joinedAt = now;
        this.leftAt = null;
    }

    public void leave(LocalDateTime now) {
        this.status = MembershipStatus.LEFT;
        this.leftAt = now;
    }

    public void promoteToLeader() {
        this.role = ChallengeMemberRole.LEADER;
    }

    public void demoteToMember() {
        this.role = ChallengeMemberRole.MEMBER;
    }

    public boolean isActive() {
        return this.status == MembershipStatus.ACTIVE;
    }

    public boolean isExpelled() {
        return this.status == MembershipStatus.EXPELLED;
    }
}
