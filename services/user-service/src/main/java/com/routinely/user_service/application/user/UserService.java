package com.routinely.user_service.application.user;

import static com.routinely.core.exception.ErrorCode.NICKNAME_ALREADY_EXISTS;
import static com.routinely.core.exception.ErrorCode.USER_NOT_FOUND;

import com.routinely.core.exception.BusinessException;
import com.routinely.user_service.application.user.dto.ProfileResult;
import com.routinely.user_service.application.user.dto.UpdateProfileCommand;
import com.routinely.user_service.domain.User;
import com.routinely.user_service.domain.UserRepository;
import com.routinely.user_service.infrastructure.config.UserProfileProperties;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;

@Service
@Transactional(readOnly = true)
public class UserService {

    private static final long SECONDS_PER_DAY = 24L * 60 * 60;

    private final UserRepository userRepository;
    private final UserProfileProperties userProfileProperties;
    private final Clock clock;

    public UserService(UserRepository userRepository, UserProfileProperties userProfileProperties, Clock clock) {
        this.userRepository = userRepository;
        this.userProfileProperties = userProfileProperties;
        this.clock = clock;
    }

    public ProfileResult getMyProfile(Long userId) {
        User user = getActiveUser(userId);
        return ProfileResult.from(user, calculateNicknameChangeableAt(user));
    }

    @Transactional
    public ProfileResult updateProfile(UpdateProfileCommand command) {
        User user = getActiveUser(command.userId());

        if (!user.getNickname().equals(command.nickname())) {
            validateNicknameCooldown(user);

            if (userRepository.existsByNickname(command.nickname())) {
                throw new BusinessException(NICKNAME_ALREADY_EXISTS);
            }

            try {
                user.updateNickname(command.nickname(), now());
                userRepository.flush();
            } catch (DataIntegrityViolationException e) {
                throw translateDuplicateException(e);
            }
        }

        user.updateBio(command.bio());

        return ProfileResult.from(user, calculateNicknameChangeableAt(user));
    }

    @Transactional
    public void withdraw(Long userId) {
        User user = getActiveUser(userId);
        user.deactivate();
    }

    private User getActiveUser(Long userId) {
        return userRepository.findByIdAndIsActiveTrue(userId)
                .orElseThrow(() -> new BusinessException(USER_NOT_FOUND));
    }

    private RuntimeException translateDuplicateException(DataIntegrityViolationException e) {
        String message = e.getMostSpecificCause().getMessage();

        if (message != null && message.contains("uq_users_nickname")) {
            return new BusinessException(NICKNAME_ALREADY_EXISTS);
        }

        return e;
    }

    private void validateNicknameCooldown(User user) {
        LocalDateTime nicknameChangeableAt = calculateNicknameChangeableAt(user);
        if (nicknameChangeableAt == null || !nicknameChangeableAt.isAfter(now())) {
            return;
        }

        long remainingDays = calculateRemainingDays(nicknameChangeableAt);
        throw new NicknameCooldownException(remainingDays, nicknameChangeableAt);
    }

    private LocalDateTime calculateNicknameChangeableAt(User user) {
        if (user.getNicknameUpdatedAt() == null) {
            return null;
        }
        return user.getNicknameUpdatedAt().plus(getNicknameCooldownDuration());
    }

    private long calculateRemainingDays(LocalDateTime nicknameChangeableAt) {
        long remainingSeconds = Duration.between(now(), nicknameChangeableAt).toSeconds();
        if (remainingSeconds <= 0) {
            return 0;
        }
        return (remainingSeconds + SECONDS_PER_DAY - 1) / SECONDS_PER_DAY;
    }

    private Duration getNicknameCooldownDuration() {
        return Duration.ofDays(userProfileProperties.nicknameCooldownDays());
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }
}
