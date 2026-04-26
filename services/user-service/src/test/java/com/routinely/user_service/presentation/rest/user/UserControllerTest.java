package com.routinely.user_service.presentation.rest.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.routinely.core.response.ApiResponse;
import com.routinely.user_service.application.user.UserService;
import com.routinely.user_service.application.user.dto.ProfileResult;
import com.routinely.user_service.application.user.dto.UpdateNicknameCommand;
import com.routinely.user_service.presentation.rest.user.dto.request.UpdateNicknameRequest;
import com.routinely.user_service.presentation.rest.user.dto.response.ProfileResponse;
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
        ProfileResult profileResult = new ProfileResult(
                1L,
                "user@routinely.com",
                "루틴러",
                "https://cdn.routinely.com/profile.jpg"
        );
        when(userService.getMyProfile(1L)).thenReturn(profileResult);

        ResponseEntity<ApiResponse<ProfileResponse>> response = controller.getMyProfile(1L);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getMessage()).isEqualTo("사용자 정보가 조회되었습니다.");
        assertThat(response.getBody().getData().userId()).isEqualTo(1L);
        assertThat(response.getBody().getData().profileImageUrl()).isEqualTo("https://cdn.routinely.com/profile.jpg");
    }

    @Test
    @DisplayName("닉네임수정_성공하면_헤더UserId와요청값으로_서비스를호출한다")
    void updateNickname_success() {
        UserService userService = mock(UserService.class);
        UserController controller = new UserController(userService);
        ProfileResult profileResult = new ProfileResult(
                1L,
                "user@routinely.com",
                "새닉네임",
                null
        );
        when(userService.updateNickname(any())).thenReturn(profileResult);

        ResponseEntity<ApiResponse<ProfileResponse>> response =
                controller.updateNickname(1L, new UpdateNicknameRequest("새닉네임"));

        ArgumentCaptor<UpdateNicknameCommand> commandCaptor = ArgumentCaptor.forClass(UpdateNicknameCommand.class);
        verify(userService).updateNickname(commandCaptor.capture());
        assertThat(commandCaptor.getValue().userId()).isEqualTo(1L);
        assertThat(commandCaptor.getValue().nickname()).isEqualTo("새닉네임");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getMessage()).isEqualTo("사용자 정보 변경이 완료되었습니다.");
        assertThat(response.getBody().getData().nickname()).isEqualTo("새닉네임");
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
