package com.routinely.challenge_service.application.dto;

import com.routinely.challenge_service.domain.challenge.Challenge;
import com.routinely.challenge_service.domain.challenge.ChallengeLifecycleStatus;
import com.routinely.challenge_service.domain.challenge.ChallengeRoutineDefinition;
import com.routinely.challenge_service.domain.member.ChallengeMemberRole;
import com.routinely.core.schedule.ScheduleType;

import java.time.LocalDate;
import java.time.LocalDateTime;

public record ChallengeResult(
        Long challengeId,
        String title,
        String description,
        String categoryCode,
        String routineTitle,
        ScheduleType scheduleType,
        Short daysOfWeek,
        Integer targetCount,
        boolean isPublic,
        String inviteCode,
        int maxMembers,
        ChallengeLifecycleStatus status,
        LocalDate startedAt,
        LocalDate endedAt,
        int currentMembers,
        LocalDateTime createdAt,
        Long creatorUserId,
        String imageUrl,
        ChallengeMemberRole myRole) {

    public static ChallengeResult from(
            Challenge challenge,
            int currentMembers,
            String inviteCode,
            ChallengeMemberRole myRole
    ) {
        ChallengeRoutineDefinition routine = challenge.getRoutineDefinition();
        return new ChallengeResult(
                challenge.getId(),
                challenge.getTitle(),
                challenge.getDescription(),
                challenge.getCategoryCode(),
                routine.getRoutineTitle(),
                routine.getScheduleType(),
                routine.getDaysOfWeek(),
                routine.getTargetCount(),
                challenge.isPublic(),
                inviteCode,
                challenge.getMaxMembers(),
                challenge.getStatus(),
                challenge.getStartedAt(),
                challenge.getEndedAt(),
                currentMembers,
                challenge.getCreatedAt(),
                challenge.getCreatorUserId(),
                challenge.getImageUrl(),
                myRole
        );
    }
}
