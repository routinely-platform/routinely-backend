package com.routinely.user_service.application.auth;

import static com.routinely.core.exception.ErrorCode.*;

import com.routinely.core.exception.BusinessException;
import com.routinely.user_service.application.auth.dto.LoginCommand;
import com.routinely.user_service.application.auth.dto.LoginResult;
import com.routinely.user_service.application.auth.dto.SignUpCommand;
import com.routinely.user_service.application.auth.dto.UserResult;
import com.routinely.user_service.domain.User;
import com.routinely.user_service.domain.UserRepository;
import com.routinely.user_service.infrastructure.jwt.JwtProvider;
import com.routinely.user_service.infrastructure.redis.RefreshTokenStore;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtProvider jwtProvider;
    private final RefreshTokenStore refreshTokenStore;

    public AuthService(UserRepository userRepository,
                       PasswordEncoder passwordEncoder,
                       JwtProvider jwtProvider,
                       RefreshTokenStore refreshTokenStore) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtProvider = jwtProvider;
        this.refreshTokenStore = refreshTokenStore;
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

    public LoginResult login(LoginCommand command) {
        User user = userRepository.findByEmailAndIsActiveTrue(command.email())
                .orElseThrow(() -> new BusinessException(INVALID_CREDENTIALS));

        if (!passwordEncoder.matches(command.password(), user.getPasswordHash())) {
            throw new BusinessException(INVALID_CREDENTIALS);
        }

        String accessToken = jwtProvider.generateAccessToken(user.getId(), user.getRole());
        String refreshToken = refreshTokenStore.save(user.getId());

        return new LoginResult(accessToken, refreshToken);
    }

    public LoginResult refresh(String refreshToken) {
        Long userId = refreshTokenStore.consumeUserId(refreshToken);
        if (userId == null) {
            throw new BusinessException(UNAUTHORIZED);
        }

        User user = userRepository.findByIdAndIsActiveTrue(userId)
                .orElseThrow(() -> new BusinessException(UNAUTHORIZED));

        String newRefreshToken = refreshTokenStore.save(userId);
        String accessToken = jwtProvider.generateAccessToken(user.getId(), user.getRole());

        return new LoginResult(accessToken, newRefreshToken);
    }

    public void logout(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            return;
        }
        refreshTokenStore.delete(refreshToken);
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
