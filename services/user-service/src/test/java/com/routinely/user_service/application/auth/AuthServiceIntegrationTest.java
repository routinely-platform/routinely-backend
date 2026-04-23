package com.routinely.user_service.application.auth;

import com.routinely.core.exception.BusinessException;
import com.routinely.user_service.application.auth.dto.SignUpCommand;
import com.routinely.user_service.application.auth.dto.UserResult;
import com.routinely.user_service.domain.User;
import com.routinely.user_service.domain.UserRepository;
import com.routinely.user_service.domain.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@DisplayName("AuthService 회원가입")
class AuthServiceIntegrationTest {

    @Autowired
    private AuthService authService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @BeforeEach
    void setUp() {
        userRepository.deleteAll();
    }

    @Test
    @DisplayName("회원가입_성공하면_User를_저장하고_UserResult를_반환한다")
    void signUp_savesUserAndReturnsUserResult() {
        SignUpCommand command = new SignUpCommand(
                "new-user@routinely.com",
                "루틴러",
                "password123!"
        );

        UserResult result = authService.signUp(command);

        assertThat(result.userId()).isNotNull();
        assertThat(result.email()).isEqualTo("new-user@routinely.com");
        assertThat(result.nickname()).isEqualTo("루틴러");

        User savedUser = userRepository.findByEmailAndIsActiveTrue("new-user@routinely.com")
                .orElseThrow();

        assertThat(savedUser.getNickname()).isEqualTo("루틴러");
        assertThat(savedUser.getRole()).isEqualTo(UserRole.USER);
        assertThat(savedUser.isActive()).isTrue();
        assertThat(savedUser.getPasswordHash()).isNotEqualTo("password123!");
        assertThat(passwordEncoder.matches("password123!", savedUser.getPasswordHash())).isTrue();
    }

    @Test
    @DisplayName("회원가입_이메일이_중복되면_예외를_던진다")
    void signUp_whenEmailAlreadyExists_throwsException() {
        userRepository.save(User.builder()
                .email("duplicate@routinely.com")
                .passwordHash(passwordEncoder.encode("password123!"))
                .nickname("기존닉네임")
                .role(UserRole.USER)
                .build());

        SignUpCommand command = new SignUpCommand(
                "duplicate@routinely.com",
                "새닉네임",
                "password123!"
        );

        assertThatThrownBy(() -> authService.signUp(command))
                .isInstanceOf(BusinessException.class)
                .hasMessage("이미 사용 중인 이메일입니다.");
    }

    @Test
    @DisplayName("회원가입_닉네임이_중복되면_예외를_던진다")
    void signUp_whenNicknameAlreadyExists_throwsException() {
        userRepository.save(User.builder()
                .email("existing@routinely.com")
                .passwordHash(passwordEncoder.encode("password123!"))
                .nickname("중복닉네임")
                .role(UserRole.USER)
                .build());

        SignUpCommand command = new SignUpCommand(
                "new@routinely.com",
                "중복닉네임",
                "password123!"
        );

        assertThatThrownBy(() -> authService.signUp(command))
                .isInstanceOf(BusinessException.class)
                .hasMessage("이미 사용 중인 닉네임입니다.");
    }
}
