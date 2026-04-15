package com.routinely.web.handler;

import com.routinely.core.exception.BusinessException;
import com.routinely.core.exception.ErrorCode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("GlobalExceptionHandler")
@WebMvcTest(controllers = GlobalExceptionHandlerTest.TestController.class)
@Import({GlobalExceptionHandler.class, GlobalExceptionHandlerTest.TestController.class})
class GlobalExceptionHandlerTest {

    @SpringBootConfiguration
    static class TestConfig {
    }

    @Autowired
    MockMvc mockMvc;

    @Test
    @DisplayName("BusinessException_ErrorCode상태값으로응답")
    void businessException_respondsWithErrorCodeStatus() throws Exception {
        mockMvc.perform(get("/test/business"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("USER_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value(ErrorCode.USER_NOT_FOUND.getMessage()));
    }

    @Test
    @DisplayName("BusinessException_커스텀메시지_응답에반영")
    void businessException_customMessage_reflectedInResponse() throws Exception {
        mockMvc.perform(get("/test/business-custom"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("USER_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("user:42 를 찾을 수 없습니다."));
    }

    @Test
    @DisplayName("MethodArgumentNotValidException_400과필드에러맵응답")
    void validation_respondsWithBadRequestAndFieldMap() throws Exception {
        String body = "{\"name\":\"\"}";

        mockMvc.perform(post("/test/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.data.name").value("이름은 필수입니다."));
    }

    @Test
    @DisplayName("일반Exception_500InternalServerError응답")
    void unexpectedException_respondsWith500() throws Exception {
        mockMvc.perform(get("/test/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.errorCode").value("INTERNAL_SERVER_ERROR"))
                .andExpect(content().string(containsString("서버 오류")));
    }

    @RestController
    @RequestMapping("/test")
    static class TestController {

        @GetMapping("/business")
        void business() {
            throw new BusinessException(ErrorCode.USER_NOT_FOUND);
        }

        @GetMapping("/business-custom")
        void businessCustom() {
            throw new BusinessException(ErrorCode.USER_NOT_FOUND, "user:42 를 찾을 수 없습니다.");
        }

        @PostMapping("/validate")
        void validate(@Valid @RequestBody TestRequest request) {
        }

        @GetMapping("/boom")
        void boom() {
            throw new IllegalStateException("예상치 못한 오류");
        }
    }

    @Getter
    @NoArgsConstructor
    static class TestRequest {
        @NotBlank(message = "이름은 필수입니다.")
        private String name;
    }
}
