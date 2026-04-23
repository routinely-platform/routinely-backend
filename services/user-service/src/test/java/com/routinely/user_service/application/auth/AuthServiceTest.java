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
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("AuthService")
class AuthServiceTest {

    private UserRepository userRepository;
    private PasswordEncoder passwordEncoder;
    private AuthService authService;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        passwordEncoder = mock(PasswordEncoder.class);
        authService = new AuthService(userRepository, passwordEncoder);
    }

    @Test
    @DisplayName("회원가입_성공하면_비밀번호를해시하고_User를저장한다")
    void signUp_savesEncodedUser() {
        SignUpCommand command = new SignUpCommand(
                "new-user@routinely.com",
                "루틴러",
                "password123!"
        );

        when(userRepository.existsByEmail(command.email())).thenReturn(false);
        when(userRepository.existsByNickname(command.nickname())).thenReturn(false);
        when(passwordEncoder.encode(command.password())).thenReturn("encoded-password");
        when(userRepository.saveAndFlush(any(User.class))).thenAnswer(invocation -> {
            User user = invocation.getArgument(0);
            return User.builder()
                    .id(1L)
                    .email(user.getEmail())
                    .passwordHash(user.getPasswordHash())
                    .nickname(user.getNickname())
                    .role(user.getRole())
                    .profileImageUrl(user.getProfileImageUrl())
                    .isActive(user.isActive())
                    .build();
        });

        UserResult result = authService.signUp(command);

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).saveAndFlush(userCaptor.capture());

        User savedUser = userCaptor.getValue();
        assertThat(savedUser.getEmail()).isEqualTo("new-user@routinely.com");
        assertThat(savedUser.getPasswordHash()).isEqualTo("encoded-password");
        assertThat(savedUser.getNickname()).isEqualTo("루틴러");
        assertThat(savedUser.getRole()).isEqualTo(UserRole.USER);
        assertThat(savedUser.getProfileImageUrl()).isNull();
        assertThat(savedUser.isActive()).isTrue();

        assertThat(result.userId()).isEqualTo(1L);
        assertThat(result.email()).isEqualTo("new-user@routinely.com");
        assertThat(result.nickname()).isEqualTo("루틴러");
    }

    @Test
    @DisplayName("회원가입_이메일이중복되면_예외를던지고_저장하지않는다")
    void signUp_whenEmailAlreadyExists_throwsException() {
        SignUpCommand command = new SignUpCommand(
                "duplicate@routinely.com",
                "루틴러",
                "password123!"
        );

        when(userRepository.existsByEmail(command.email())).thenReturn(true);

        assertThatThrownBy(() -> authService.signUp(command))
                .isInstanceOf(BusinessException.class)
                .hasMessage("이미 사용 중인 이메일입니다.");

        verify(userRepository, never()).existsByNickname(any());
        verify(passwordEncoder, never()).encode(any());
        verify(userRepository, never()).save(any());
        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("회원가입_닉네임이중복되면_예외를던지고_저장하지않는다")
    void signUp_whenNicknameAlreadyExists_throwsException() {
        SignUpCommand command = new SignUpCommand(
                "new-user@routinely.com",
                "중복닉네임",
                "password123!"
        );

        when(userRepository.existsByEmail(command.email())).thenReturn(false);
        when(userRepository.existsByNickname(command.nickname())).thenReturn(true);

        assertThatThrownBy(() -> authService.signUp(command))
                .isInstanceOf(BusinessException.class)
                .hasMessage("이미 사용 중인 닉네임입니다.");

        verify(passwordEncoder, never()).encode(any());
        verify(userRepository, never()).save(any());
        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("회원가입_DB이메일유니크제약위반이면_이메일중복예외로변환한다")
    void signUp_whenEmailUniqueConstraintFails_throwsBusinessException() {
        SignUpCommand command = new SignUpCommand(
                "new-user@routinely.com",
                "루틴러",
                "password123!"
        );

        when(userRepository.existsByEmail(command.email())).thenReturn(false);
        when(userRepository.existsByNickname(command.nickname())).thenReturn(false);
        when(passwordEncoder.encode(command.password())).thenReturn("encoded-password");
        when(userRepository.saveAndFlush(any(User.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate", new RuntimeException("uq_users_email")));

        assertThatThrownBy(() -> authService.signUp(command))
                .isInstanceOf(BusinessException.class)
                .hasMessage("이미 사용 중인 이메일입니다.");
    }

    @Test
    @DisplayName("회원가입_DB닉네임유니크제약위반이면_닉네임중복예외로변환한다")
    void signUp_whenNicknameUniqueConstraintFails_throwsBusinessException() {
        SignUpCommand command = new SignUpCommand(
                "new-user@routinely.com",
                "루틴러",
                "password123!"
        );

        when(userRepository.existsByEmail(command.email())).thenReturn(false);
        when(userRepository.existsByNickname(command.nickname())).thenReturn(false);
        when(passwordEncoder.encode(command.password())).thenReturn("encoded-password");
        when(userRepository.saveAndFlush(any(User.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate", new RuntimeException("uq_users_nickname")));

        assertThatThrownBy(() -> authService.signUp(command))
                .isInstanceOf(BusinessException.class)
                .hasMessage("이미 사용 중인 닉네임입니다.");
    }
}
