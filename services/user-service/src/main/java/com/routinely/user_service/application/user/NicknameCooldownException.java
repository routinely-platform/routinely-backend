package com.routinely.user_service.application.user;

import com.routinely.core.exception.BusinessException;
import com.routinely.core.exception.ErrorCode;
import java.time.LocalDateTime;

public class NicknameCooldownException extends BusinessException {

    private final long remainingDays;
    private final LocalDateTime nicknameChangeableAt;

    public NicknameCooldownException(long remainingDays, LocalDateTime nicknameChangeableAt) {
        super(
                ErrorCode.NICKNAME_CHANGE_COOLDOWN_ACTIVE,
                "닉네임은 아직 변경할 수 없습니다. " + remainingDays + "일 후 다시 시도해주세요."
        );
        this.remainingDays = remainingDays;
        this.nicknameChangeableAt = nicknameChangeableAt;
    }

    public long getRemainingDays() {
        return remainingDays;
    }

    public LocalDateTime getNicknameChangeableAt() {
        return nicknameChangeableAt;
    }
}
