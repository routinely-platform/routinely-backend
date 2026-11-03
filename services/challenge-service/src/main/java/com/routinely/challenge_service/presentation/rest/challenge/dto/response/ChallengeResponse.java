package com.routinely.challenge_service.presentation.rest.challenge.dto.response;

import com.routinely.challenge_service.application.dto.ChallengeResult;
import com.routinely.challenge_service.domain.challenge.ChallengeLifecycleStatus;
import com.routinely.challenge_service.domain.member.ChallengeMemberRole;
import com.routinely.core.schedule.ScheduleType;
import com.routinely.core.schedule.Weekdays;

import java.time.LocalDate;
import java.util.List;
import java.time.LocalDateTime;

public record ChallengeResponse(
        Long challengeId,
        String title,
        String description,
        String categoryCode,
        String routineTitle,
        ScheduleType scheduleType,
        List<String> daysOfWeek,
        Integer targetCount,
        boolean isPublic,
        String inviteCode,
        int maxMembers,
        int currentMembers,
        ChallengeLifecycleStatus status,
        LocalDate startedAt,
        LocalDate endedAt,
        LocalDateTime createdAt,
        Long creatorUserId,
        String imageUrl,
        ChallengeMemberRole myRole) {

    public static ChallengeResponse from(ChallengeResult result) {
        return new ChallengeResponse(
                result.challengeId(),
                result.title(),
                result.description(),
                result.categoryCode(),
                result.routineTitle(),
                result.scheduleType(),
                toDayCodes(result.daysOfWeek()),
                result.targetCount(),
                result.isPublic(),
                result.inviteCode(),
                result.maxMembers(),
                result.currentMembers(),
                result.status(),
                result.startedAt(),
                result.endedAt(),
                result.createdAt(),
                result.creatorUserId(),
                result.imageUrl(),
                result.myRole()
        );
    }

    /** SPECIFIC_DAYS가 아니면 null — routine-service 응답과 같은 모양이다. */
    private static List<String> toDayCodes(Short daysOfWeek) {
        return daysOfWeek == null ? null : Weekdays.toCodes(daysOfWeek);
    }
}
