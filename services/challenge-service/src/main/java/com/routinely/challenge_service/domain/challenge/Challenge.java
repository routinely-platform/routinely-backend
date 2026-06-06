package com.routinely.challenge_service.domain.challenge;

import com.routinely.challenge_service.application.dto.CreateChallengeCommand;
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
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

@Entity
@Table(name = "challenges")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class Challenge extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "creator_user_id", nullable = false)
    private Long creatorUserId;

    @Column(nullable = false, length = 100)
    private String title;

    @Column(length = 500)
    private String description;

    @Column(name = "is_public", nullable = false)
    @Builder.Default
    private boolean isPublic = true;

    @Column(name = "invite_code", unique = true, length = 20)
    private String inviteCode;

    @Column(name = "max_members", nullable = false)
    private int maxMembers;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private ChallengeLifecycleStatus status = ChallengeLifecycleStatus.WAITING;

    @Column(name = "category_code", nullable = false, length = 30)
    private String categoryCode;

    @Column(name = "started_at", nullable = false)
    private LocalDate startedAt;

    @Column(name = "ended_at", nullable = false)
    private LocalDate endedAt;

    public static Challenge create(CreateChallengeCommand command, Long creatorUserId, String inviteCode) {
        return Challenge.builder()
                .creatorUserId(creatorUserId)
                .title(command.title())
                .description(command.description())
                .isPublic(command.isPublic())
                .inviteCode(inviteCode)
                .maxMembers(command.maxMembers())
                .categoryCode(command.categoryCode())
                .startedAt(command.startedAt())
                .endedAt(command.endedAt())
                .build();
    }

    public void updateTitle(String title) {
        this.title = title;
    }

    public void updateDescription(String description) {
        this.description = description;
    }

    public void makePublic() {
        this.isPublic = true;
    }

    public void makePrivate(String inviteCode) {
        this.isPublic = false;
        if (this.inviteCode == null) {
            this.inviteCode = inviteCode;
        }
    }

    public void updateMaxMembers(int maxMembers) {
        this.maxMembers = maxMembers;
    }

    public void updateStartedAt(LocalDate startedAt) {
        this.startedAt = startedAt;
    }

    public void updateEndedAt(LocalDate endedAt) {
        this.endedAt = endedAt;
    }

    public void end() {
        this.status = ChallengeLifecycleStatus.ENDED;
    }
}
