package com.routinely.challenge_service.application.dto;

import java.time.LocalDate;

public record UpdateChallengeCommand(String title,
                                     String description,
                                     Boolean isPublic,
                                     Integer maxMembers,
                                     LocalDate startedAt,
                                     LocalDate endedAt,
                                     String routineTitle,
                                     String routinePreferredTime) {
}
