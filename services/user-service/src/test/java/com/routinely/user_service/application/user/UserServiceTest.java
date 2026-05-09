package com.routinely.user_service.application.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.routinely.core.exception.BusinessException;
import com.routinely.user_service.application.user.dto.ProfileResult;
import com.routinely.user_service.application.user.dto.UpdateProfileCommand;
import com.routinely.user_service.domain.User;
import com.routinely.user_service.domain.UserRepository;
import com.routinely.user_service.domain.UserRole;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

@DisplayName("UserService")
class UserServiceTest {

    private UserRepository userRepository;
    private UserService userService;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        userService = new UserService(userRepository);
    }

    @Test
    @DisplayName("내프로필조회_성공하면_ProfileResult를_반환한다")
    void getMyProfile_success() {
        LocalDateTime createdAt = LocalDateTime.of(2024, 3, 15, 10, 0);
        User user = createUser(1L, "루틴러", "매일 조금씩 성장하는 중", "https://cdn.routinely.com/profile.jpg", createdAt);
        when(userRepository.findByIdAndIsActiveTrue(1L)).thenReturn(Optional.of(user));

        ProfileResult result = userService.getMyProfile(1L);

        assertThat(result.userId()).isEqualTo(1L);
        assertThat(result.email()).isEqualTo("user1@routinely.com");
        assertThat(result.nickname()).isEqualTo("루틴러");
        assertThat(result.bio()).isEqualTo("매일 조금씩 성장하는 중");
        assertThat(result.profileImageUrl()).isEqualTo("https://cdn.routinely.com/profile.jpg");
        assertThat(result.createdAt()).isEqualTo(createdAt);
    }

    @Test
    @DisplayName("내프로필조회_활성사용자가없으면_예외를던진다")
    void getMyProfile_whenUserNotFound_throwsException() {
        when(userRepository.findByIdAndIsActiveTrue(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.getMyProfile(1L))
                .isInstanceOf(BusinessException.class)
                .hasMessage("사용자를 찾을 수 없습니다.");
    }

    @Test
    @DisplayName("프로필수정_성공하면_기존프로필이미지URL과createdAt을보존한다")
    void updateProfile_preservesProfileImageUrlAndCreatedAt() {
        LocalDateTime createdAt = LocalDateTime.of(2024, 3, 15, 10, 0);
        User user = createUser(1L, "기존닉네임", null, "https://cdn.routinely.com/profile.jpg", createdAt);
        when(userRepository.findByIdAndIsActiveTrue(1L)).thenReturn(Optional.of(user));
        when(userRepository.existsByNickname("새닉네임")).thenReturn(false);

        ProfileResult result = userService.updateProfile(new UpdateProfileCommand(1L, "새닉네임", "매일 조금씩 성장하는 중"));

        assertThat(user.getNickname()).isEqualTo("새닉네임");
        assertThat(user.getBio()).isEqualTo("매일 조금씩 성장하는 중");
        assertThat(user.getProfileImageUrl()).isEqualTo("https://cdn.routinely.com/profile.jpg");
        assertThat(result.nickname()).isEqualTo("새닉네임");
        assertThat(result.bio()).isEqualTo("매일 조금씩 성장하는 중");
        assertThat(result.profileImageUrl()).isEqualTo("https://cdn.routinely.com/profile.jpg");
        assertThat(result.createdAt()).isEqualTo(createdAt);
        verify(userRepository).flush();
    }

    @Test
    @DisplayName("프로필수정_동일닉네임이면_중복검사를하지않고_bio만수정한다")
    void updateProfile_whenSameNickname_skipsDuplicateCheck() {
        User user = createUser(1L, "루틴러", null, null, null);
        when(userRepository.findByIdAndIsActiveTrue(1L)).thenReturn(Optional.of(user));

        ProfileResult result = userService.updateProfile(new UpdateProfileCommand(1L, "루틴러", "새 소개"));

        assertThat(result.nickname()).isEqualTo("루틴러");
        assertThat(result.bio()).isEqualTo("새 소개");
        verify(userRepository, never()).existsByNickname(anyString());
        verify(userRepository, never()).flush();
    }

    @Test
    @DisplayName("프로필수정_닉네임이중복되면_예외를던지고_변경하지않는다")
    void updateProfile_whenNicknameAlreadyExists_throwsException() {
        User user = createUser(1L, "기존닉네임", "기존 소개", null, null);
        when(userRepository.findByIdAndIsActiveTrue(1L)).thenReturn(Optional.of(user));
        when(userRepository.existsByNickname("중복닉네임")).thenReturn(true);

        assertThatThrownBy(() -> userService.updateProfile(new UpdateProfileCommand(1L, "중복닉네임", "새 소개")))
                .isInstanceOf(BusinessException.class)
                .hasMessage("이미 사용 중인 닉네임입니다.");

        assertThat(user.getNickname()).isEqualTo("기존닉네임");
        assertThat(user.getBio()).isEqualTo("기존 소개");
        verify(userRepository, never()).flush();
    }

    @Test
    @DisplayName("프로필수정_DB유니크제약위반이면_닉네임중복예외로변환한다")
    void updateProfile_whenUniqueConstraintFails_throwsBusinessException() {
        User user = createUser(1L, "기존닉네임", null, null, null);
        when(userRepository.findByIdAndIsActiveTrue(1L)).thenReturn(Optional.of(user));
        when(userRepository.existsByNickname("새닉네임")).thenReturn(false);
        doThrow(new DataIntegrityViolationException("duplicate", new RuntimeException("uq_users_nickname")))
                .when(userRepository).flush();

        assertThatThrownBy(() -> userService.updateProfile(new UpdateProfileCommand(1L, "새닉네임", "소개")))
                .isInstanceOf(BusinessException.class)
                .hasMessage("이미 사용 중인 닉네임입니다.");
    }

    @Test
    @DisplayName("프로필수정_bio가빈문자열이면_null로변환한다")
    void updateProfile_whenBioBlank_setsNull() {
        User user = createUser(1L, "루틴러", "기존 소개", null, null);
        when(userRepository.findByIdAndIsActiveTrue(1L)).thenReturn(Optional.of(user));

        ProfileResult result = userService.updateProfile(new UpdateProfileCommand(1L, "루틴러", "   "));

        assertThat(user.getBio()).isNull();
        assertThat(result.bio()).isNull();
        verify(userRepository, never()).existsByNickname(anyString());
        verify(userRepository, never()).flush();
    }

    @Test
    @DisplayName("회원탈퇴_성공하면_사용자를비활성화한다")
    void withdraw_deactivatesUser() {
        User user = createUser(1L, "루틴러", null, null, null);
        when(userRepository.findByIdAndIsActiveTrue(1L)).thenReturn(Optional.of(user));

        userService.withdraw(1L);

        assertThat(user.isActive()).isFalse();
    }

    @Test
    @DisplayName("회원탈퇴_활성사용자가없으면_예외를던진다")
    void withdraw_whenUserNotFound_throwsException() {
        when(userRepository.findByIdAndIsActiveTrue(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.withdraw(1L))
                .isInstanceOf(BusinessException.class)
                .hasMessage("사용자를 찾을 수 없습니다.");
    }

    private User createUser(Long id, String nickname, String bio, String profileImageUrl, LocalDateTime createdAt) {
        User user = User.builder()
                .id(id)
                .email("user" + id + "@routinely.com")
                .passwordHash("encoded-password")
                .nickname(nickname)
                .role(UserRole.USER)
                .bio(bio)
                .profileImageUrl(profileImageUrl)
                .isActive(true)
                .build();

        if (createdAt != null) {
            ReflectionTestUtils.setField(user, "createdAt", createdAt);
        }

        return user;
    }
}
