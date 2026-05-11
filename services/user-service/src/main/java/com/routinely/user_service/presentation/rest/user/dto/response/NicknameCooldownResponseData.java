package com.routinely.user_service.presentation.rest.user.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDateTime;

public record NicknameCooldownResponseData(
        @JsonProperty("remaining_days")
        long remainingDays,
        @JsonProperty("nickname_changeable_at")
        LocalDateTime nicknameChangeableAt
) {
}
