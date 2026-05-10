package com.routinely.user_service.presentation.rest.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.routinely.core.response.ApiResponse;
import com.routinely.user_service.application.user.UserService;
import com.routinely.user_service.application.user.dto.ProfileResult;
import com.routinely.user_service.application.user.dto.UpdateProfileCommand;
import com.routinely.user_service.presentation.rest.user.dto.request.UpdateProfileRequest;
import com.routinely.user_service.presentation.rest.user.dto.response.ProfileResponse;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

@DisplayName("UserController")
class UserControllerTest {

    @Test
    @DisplayName("내프로필조회_성공하면_프로필응답을반환한다")
    void getMyProfile_success() {
        UserService userService = mock(UserService.class);
        UserController controller = new UserController(userService);
        LocalDateTime createdAt = LocalDateTime.of(2024, 3, 15, 10, 0);
        LocalDateTime nicknameChangeableAt = LocalDateTime.of(2024, 4, 14, 10, 0);
        ProfileResult profileResult = new ProfileResult(
                1L,
                "user@routinely.com",
                "루틴러",
                "매일 조금씩 성장하는 중",
                "https://cdn.routinely.com/profile.jpg",
                createdAt,
                nicknameChangeableAt
        );
        when(userService.getMyProfile(1L)).thenReturn(profileResult);

        ResponseEntity<ApiResponse<ProfileResponse>> response = controller.getMyProfile(1L);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getMessage()).isEqualTo("사용자 정보가 조회되었습니다.");
        assertThat(response.getBody().getData().userId()).isEqualTo(1L);
        assertThat(response.getBody().getData().bio()).isEqualTo("매일 조금씩 성장하는 중");
        assertThat(response.getBody().getData().profileImageUrl()).isEqualTo("https://cdn.routinely.com/profile.jpg");
        assertThat(response.getBody().getData().createdAt()).isEqualTo(createdAt);
        assertThat(response.getBody().getData().nicknameChangeableAt()).isEqualTo(nicknameChangeableAt);
    }

    @Test
    @DisplayName("프로필수정_성공하면_헤더UserId와요청값으로_서비스를호출한다")
    void updateProfile_success() {
        UserService userService = mock(UserService.class);
        UserController controller = new UserController(userService);
        LocalDateTime createdAt = LocalDateTime.of(2024, 3, 15, 10, 0);
        LocalDateTime nicknameChangeableAt = LocalDateTime.of(2024, 4, 14, 10, 0);
        ProfileResult profileResult = new ProfileResult(
                1L,
                "user@routinely.com",
                "새닉네임",
                "매일 조금씩 성장하는 중",
                null,
                createdAt,
                nicknameChangeableAt
        );
        when(userService.updateProfile(any())).thenReturn(profileResult);

        ResponseEntity<ApiResponse<ProfileResponse>> response =
                controller.updateProfile(1L, new UpdateProfileRequest("새닉네임", "매일 조금씩 성장하는 중"));

        ArgumentCaptor<UpdateProfileCommand> commandCaptor = ArgumentCaptor.forClass(UpdateProfileCommand.class);
        verify(userService).updateProfile(commandCaptor.capture());
        assertThat(commandCaptor.getValue().userId()).isEqualTo(1L);
        assertThat(commandCaptor.getValue().nickname()).isEqualTo("새닉네임");
        assertThat(commandCaptor.getValue().bio()).isEqualTo("매일 조금씩 성장하는 중");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getMessage()).isEqualTo("사용자 정보 변경이 완료되었습니다.");
        assertThat(response.getBody().getData().nickname()).isEqualTo("새닉네임");
        assertThat(response.getBody().getData().bio()).isEqualTo("매일 조금씩 성장하는 중");
        assertThat(response.getBody().getData().createdAt()).isEqualTo(createdAt);
        assertThat(response.getBody().getData().nicknameChangeableAt()).isEqualTo(nicknameChangeableAt);
    }

    @Test
    @DisplayName("회원탈퇴_성공하면_서비스를호출하고_성공응답을반환한다")
    void withdraw_success() {
        UserService userService = mock(UserService.class);
        UserController controller = new UserController(userService);

        ResponseEntity<ApiResponse<Void>> response = controller.withdraw(1L);

        verify(userService).withdraw(1L);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getMessage()).isEqualTo("회원 탈퇴가 완료되었습니다.");
        assertThat(response.getBody().getData()).isNull();
    }
}
