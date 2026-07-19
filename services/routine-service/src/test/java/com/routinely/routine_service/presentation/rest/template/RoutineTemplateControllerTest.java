package com.routinely.routine_service.presentation.rest.template;

import com.routinely.core.response.ApiResponse;
import com.routinely.routine_service.application.template.RoutineTemplateService;
import com.routinely.routine_service.application.template.dto.RoutineTemplateResult;
import com.routinely.routine_service.domain.template.RepeatType;
import com.routinely.routine_service.presentation.rest.template.dto.request.CreateRoutineTemplateRequest;
import com.routinely.routine_service.presentation.rest.template.dto.response.RoutineTemplateResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("RoutineTemplateController")
class RoutineTemplateControllerTest {

    private final RoutineTemplateService routineTemplateService = mock(RoutineTemplateService.class);
    private final RoutineTemplateController controller = new RoutineTemplateController(routineTemplateService);

    private static final RoutineTemplateResult RESULT =
            new RoutineTemplateResult(10L, "아침 러닝 30분", "EXERCISE", RepeatType.WEEKLY_N, 3, null);

    @Test
    @DisplayName("생성하면_201과생성메시지를반환한다")
    void createTemplate_returns201WithBody() {
        when(routineTemplateService.create(any())).thenReturn(RESULT);

        ResponseEntity<ApiResponse<RoutineTemplateResponse>> response = controller.createTemplate(
                1L, new CreateRoutineTemplateRequest("아침 러닝 30분", "EXERCISE", "WEEKLY_N", 3));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().getMessage()).isEqualTo("루틴 템플릿이 생성되었습니다.");
        assertThat(response.getBody().getData().templateId()).isEqualTo(10L);
        assertThat(response.getBody().getData().repeatType()).isEqualTo("WEEKLY_N");
    }

    @Test
    @DisplayName("목록조회는_카테고리필터를서비스에그대로전달한다")
    void getMyTemplates_passesCategoryCodeThrough() {
        when(routineTemplateService.getMyTemplates(1L, "EXERCISE")).thenReturn(List.of(RESULT));

        ResponseEntity<ApiResponse<List<RoutineTemplateResponse>>> response =
                controller.getMyTemplates(1L, "EXERCISE");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().getMessage()).isEqualTo("루틴 템플릿 목록이 조회되었습니다.");
        assertThat(response.getBody().getData()).hasSize(1);
        verify(routineTemplateService).getMyTemplates(1L, "EXERCISE");
    }

    @Test
    @DisplayName("삭제하면_서비스에위임하고삭제메시지를반환한다")
    void deleteTemplate_delegatesToService() {
        ResponseEntity<ApiResponse<Void>> response = controller.deleteTemplate(1L, 10L);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().getMessage()).isEqualTo("루틴 템플릿이 삭제되었습니다.");
        verify(routineTemplateService).delete(10L, 1L);
    }
}
