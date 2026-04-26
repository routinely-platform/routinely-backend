package com.routinely.user_service.application.user;

import static com.routinely.core.exception.ErrorCode.NICKNAME_ALREADY_EXISTS;
import static com.routinely.core.exception.ErrorCode.USER_NOT_FOUND;

import com.routinely.core.exception.BusinessException;
import com.routinely.user_service.application.user.dto.ProfileResult;
import com.routinely.user_service.application.user.dto.UpdateNicknameCommand;
import com.routinely.user_service.domain.User;
import com.routinely.user_service.domain.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class UserService {

    private final UserRepository userRepository;

    public UserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public ProfileResult getMyProfile(Long userId) {
        User user = getActiveUser(userId);
        return ProfileResult.from(user);
    }

    @Transactional
    public ProfileResult updateNickname(UpdateNicknameCommand command) {
        User user = getActiveUser(command.userId());

        if (user.getNickname().equals(command.nickname())) {
            return ProfileResult.from(user);
        }

        if (userRepository.existsByNickname(command.nickname())) {
            throw new BusinessException(NICKNAME_ALREADY_EXISTS);
        }

        try {
            user.updateNickname(command.nickname());
            userRepository.flush();
        } catch (DataIntegrityViolationException e) {
            throw translateDuplicateException(e);
        }

        return ProfileResult.from(user);
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
}
