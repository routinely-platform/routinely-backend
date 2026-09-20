package com.routinely.routine_service.presentation.rest.routine;

import com.routinely.routine_service.application.routine.RoutineService;
import com.routinely.routine_service.application.routine.dto.RoutineResult;
import com.routinely.routine_service.application.execution.RoutineExecutionService;
import com.routinely.routine_service.presentation.rest.execution.RoutineExecutionController;
import com.routinely.web.handler.GlobalExceptionHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = {RoutineController.class, RoutineExecutionController.class})
@Import(GlobalExceptionHandler.class)
@DisplayName("루틴 생성·수정·다중 완료 HTTP 계약")
class RoutineApiTest {
    @Autowired MockMvc mvc;
    @MockitoBean RoutineService routines;
    @MockitoBean RoutineExecutionService executions;
    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Asia/Seoul"));

    private RoutineResult result() {
        return new RoutineResult(1L, null, "물 한 잔", null, TODAY, null, null, null, true, "HEALTH", "DAILY", null, null);
    }

    @ParameterizedTest @ValueSource(strings = {"\"routineTemplateId\":1", "\"title\":\"물 한 잔\",\"categoryCode\":\"HEALTH\",\"scheduleType\":\"DAILY\""})
    @DisplayName("템플릿 또는 직접 정의로 종료일 없는 루틴을 생성한다")
    void start_eitherSourceReturns201(String source) throws Exception {
        when(routines.start(any())).thenReturn(result());
        mvc.perform(post("/api/v1/routines").header("X-User-Id", 1)
                .contentType(MediaType.APPLICATION_JSON).content("{" + source + ",\"startedAt\":\"" + TODAY + "\"}"))
                .andExpect(status().isCreated());
    }

    @ParameterizedTest @ValueSource(strings = {"", "\"routineTemplateId\":1,\"title\":\"중복\",", "\"title\":\"빈 정의\","})
    @DisplayName("정의가 없거나 불완전하거나 템플릿과 함께 있으면 거부한다")
    void start_invalidSourceReturns400(String source) throws Exception {
        mvc.perform(post("/api/v1/routines").header("X-User-Id", 1).contentType(MediaType.APPLICATION_JSON)
                .content("{" + source + "\"startedAt\":\"" + TODAY + "\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(routines);
    }

    @Test @DisplayName("90일 전과 366일 기간은 허용하고 그 경계를 넘으면 거부한다")
    void start_periodBoundaries() throws Exception {
        when(routines.start(any())).thenReturn(result());
        mvc.perform(post("/api/v1/routines").header("X-User-Id", 1).contentType(MediaType.APPLICATION_JSON)
                .content("{\"routineTemplateId\":1,\"startedAt\":\"" + TODAY.minusDays(90) + "\",\"endedAt\":\"" + TODAY.plusDays(275) + "\"}"))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/v1/routines").header("X-User-Id", 1).contentType(MediaType.APPLICATION_JSON)
                .content("{\"routineTemplateId\":1,\"startedAt\":\"" + TODAY.minusDays(91) + "\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/routines").header("X-User-Id", 1).contentType(MediaType.APPLICATION_JSON)
                .content("{\"routineTemplateId\":1,\"startedAt\":\"" + TODAY + "\",\"endedAt\":\"" + TODAY.plusDays(366) + "\"}"))
                .andExpect(status().isBadRequest());
    }

    @ParameterizedTest @ValueSource(strings = {
            "{\"clearEndedAt\":true,\"endedAt\":\"2026-10-01\"}",
            "{\"clearPreferredTime\":true,\"preferredTime\":\"07:00:00\"}",
            "{\"clearPreferredDays\":true,\"preferredDays\":[\"MON\"]}",
            "{\"preferredDays\":[null]}", "{\"daysOfWeek\":[\"MON\"]}",
            "{\"scheduleType\":\"WEEKLY_COUNT\",\"targetCount\":7}",
            "{\"scheduleType\":\"MONTHLY_COUNT\",\"targetCount\":29}", "{\"title\":\" \"}"})
    @DisplayName("PATCH의 상충하는 해제 옵션과 잘못된 스케줄을 거부한다")
    void update_invalidFieldsReturns400(String body) throws Exception {
        mvc.perform(patch("/api/v1/routines/1").header("X-User-Id", 1).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(routines);
    }

    @Test @DisplayName("다중 완료의 빈 목록은 생성 건수 0으로 응답한다")
    void bulk_emptyReturnsZero() throws Exception {
        when(executions.bulkComplete(1L, TODAY, List.of())).thenReturn(List.of());
        mvc.perform(post("/api/v1/routine-executions/bulk-complete").header("X-User-Id", 1)
                .contentType(MediaType.APPLICATION_JSON).content("{\"scheduledDate\":\"" + TODAY + "\",\"routineIds\":[]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.createdCount").value(0));
    }

    @ParameterizedTest @ValueSource(strings = {"[1,1]", "[null]", "[-1]", "null"})
    @DisplayName("다중 완료의 중복 또는 잘못된 루틴 ID는 거부한다")
    void bulk_invalidIdsReturns400(String ids) throws Exception {
        mvc.perform(post("/api/v1/routine-executions/bulk-complete").header("X-User-Id", 1)
                .contentType(MediaType.APPLICATION_JSON).content("{\"scheduledDate\":\"" + TODAY + "\",\"routineIds\":" + ids + "}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(executions);
    }

    @Test @DisplayName("인증 메모 길이와 사진 시그니처 및 5MB 제한은 요청 계층에서 검증한다")
    void complete_invalidInputReturns400() throws Exception {
        mvc.perform(multipart("/api/v1/routines/1/executions/" + TODAY + "/complete")
                .header("X-User-Id", 1).param("memo", "가".repeat(501))).andExpect(status().isBadRequest());
        mvc.perform(multipart("/api/v1/routines/1/executions/" + TODAY + "/complete")
                .file(new MockMultipartFile("photo", "a.jpg", "image/jpeg", new byte[]{1,2,3}))
                .header("X-User-Id", 1)).andExpect(status().isBadRequest());
        mvc.perform(multipart("/api/v1/routines/1/executions/" + TODAY + "/complete")
                .file(new MockMultipartFile("photo", "a.jpg", "image/jpeg", new byte[5 * 1024 * 1024 + 1]))
                .header("X-User-Id", 1)).andExpect(status().isBadRequest());
        verifyNoInteractions(executions);
    }
}
