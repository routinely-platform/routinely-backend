package com.routinely.challenge_service.presentation.rest.challenge.dto.response;

import com.routinely.challenge_service.application.dto.ChallengeListResult;
import com.routinely.challenge_service.application.dto.ChallengeResult;
import com.routinely.challenge_service.domain.challenge.ChallengeLifecycleStatus;
import com.routinely.challenge_service.domain.member.ChallengeMemberRole;
import com.routinely.core.schedule.ScheduleType;
import com.routinely.core.schedule.Weekdays;

import java.time.LocalDate;
import java.util.List;

public record ChallengeListResponse(
        List<ChallengeSummaryResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages) {

    public static ChallengeListResponse from(ChallengeListResult result) {
        return new ChallengeListResponse(
                result.content().stream().map(ChallengeSummaryResponse::from).toList(),
                result.page(),
                result.size(),
                result.totalElements(),
                result.totalPages()
        );
    }

    public record ChallengeSummaryResponse(
            Long challengeId,
            String title,
            String description,
            String categoryCode,
            String routineTitle,
            ScheduleType scheduleType,
            List<String> daysOfWeek,
            Integer targetCount,
            ChallengeLifecycleStatus status,
            int currentMembers,
            int maxMembers,
            LocalDate startedAt,
            LocalDate endedAt,
            String imageUrl,
            ChallengeMemberRole myRole
    ) {

        public static ChallengeSummaryResponse from(ChallengeResult result) {
            return new ChallengeSummaryResponse(
                    result.challengeId(),
                    result.title(),
                    result.description(),
                    result.categoryCode(),
                    result.routineTitle(),
                    result.scheduleType(),
                    toDayCodes(result.daysOfWeek()),
                    result.targetCount(),
                    result.status(),
                    result.currentMembers(),
                    result.maxMembers(),
                    result.startedAt(),
                    result.endedAt(),
                    result.imageUrl(),
                    result.myRole()
            );
        }

        /** SPECIFIC_DAYS가 아니면 null — routine-service 응답과 같은 모양이다. */
        private static List<String> toDayCodes(Short daysOfWeek) {
            return daysOfWeek == null ? null : Weekdays.toCodes(daysOfWeek);
        }
    }
}
