package com.routinely.user_service.application.user.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDateTime;

public record NicknameCooldownData(
        @JsonProperty("remaining_days")
        long remainingDays,
        @JsonProperty("nickname_changeable_at")
        LocalDateTime nicknameChangeableAt
) {
}
