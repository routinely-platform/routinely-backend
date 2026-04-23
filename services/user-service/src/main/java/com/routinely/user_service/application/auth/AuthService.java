package com.routinely.user_service.application.auth;

import static com.routinely.core.exception.ErrorCode.*;

import com.routinely.core.exception.BusinessException;
import com.routinely.user_service.application.auth.dto.SignUpCommand;
import com.routinely.user_service.application.auth.dto.UserResult;
import com.routinely.user_service.domain.User;
import com.routinely.user_service.domain.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public UserResult signUp(SignUpCommand command) {
        if (userRepository.existsByEmail(command.email())) {
            throw new BusinessException(EMAIL_ALREADY_EXISTS);
        }

        if (userRepository.existsByNickname(command.nickname())) {
            throw new BusinessException(NICKNAME_ALREADY_EXISTS);
        }

        String encodedPassword = passwordEncoder.encode(command.password());
        User user = User.createUser(command.email(), encodedPassword, command.nickname());
        try {
            User savedUser = userRepository.saveAndFlush(user);
            return UserResult.from(savedUser);
        } catch (DataIntegrityViolationException e) {
            throw translateDuplicateException(e);
        }
    }

    private RuntimeException translateDuplicateException(DataIntegrityViolationException e) {
        String message = e.getMostSpecificCause().getMessage();

        if (message.contains("uq_users_email")) {
            return new BusinessException(EMAIL_ALREADY_EXISTS);
        }

        if (message.contains("uq_users_nickname")) {
            return new BusinessException(NICKNAME_ALREADY_EXISTS);
        }

        return e;
    }
}
