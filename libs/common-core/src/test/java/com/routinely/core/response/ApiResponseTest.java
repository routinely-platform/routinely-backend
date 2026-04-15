package com.routinely.core.response;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ApiResponse")
class ApiResponseTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Nested
    @DisplayName("ok")
    class Ok {

        @Test
        @DisplayName("ok_데이터포함_성공응답생성")
        void ok_withData_createsSuccessResponse() {
            ApiResponse<String> response = ApiResponse.ok("조회에 성공했습니다.", "payload");

            assertThat(response.isSuccess()).isTrue();
            assertThat(response.getMessage()).isEqualTo("조회에 성공했습니다.");
            assertThat(response.getData()).isEqualTo("payload");
            assertThat(response.getErrorCode()).isNull();
        }

        @Test
        @DisplayName("ok_메시지만_데이터는null")
        void ok_messageOnly_dataIsNull() {
            ApiResponse<Void> response = ApiResponse.ok("삭제되었습니다.");

            assertThat(response.isSuccess()).isTrue();
            assertThat(response.getMessage()).isEqualTo("삭제되었습니다.");
            assertThat(response.getData()).isNull();
            assertThat(response.getErrorCode()).isNull();
        }
    }

    @Nested
    @DisplayName("fail")
    class Fail {

        @Test
        @DisplayName("fail_에러코드와메시지_실패응답생성")
        void fail_codeAndMessage_createsFailResponse() {
            ApiResponse<Void> response = ApiResponse.fail("CHALLENGE_NOT_FOUND", "챌린지를 찾을 수 없습니다.");

            assertThat(response.isSuccess()).isFalse();
            assertThat(response.getErrorCode()).isEqualTo("CHALLENGE_NOT_FOUND");
            assertThat(response.getMessage()).isEqualTo("챌린지를 찾을 수 없습니다.");
            assertThat(response.getData()).isNull();
        }

        @Test
        @DisplayName("fail_데이터포함_유효성에러맵포함")
        void fail_withData_includesValidationErrorMap() {
            Map<String, String> errors = Map.of("email", "이메일 형식이 올바르지 않습니다.");

            ApiResponse<Map<String, String>> response =
                    ApiResponse.fail("VALIDATION_FAILED", "유효성 검사에 실패했습니다.", errors);

            assertThat(response.isSuccess()).isFalse();
            assertThat(response.getErrorCode()).isEqualTo("VALIDATION_FAILED");
            assertThat(response.getData()).containsEntry("email", "이메일 형식이 올바르지 않습니다.");
        }
    }

    @Nested
    @DisplayName("json")
    class JsonSerialization {

        @Test
        @DisplayName("ok_메시지만_NULL필드는직렬화제외")
        void ok_messageOnly_excludesNullFieldsDuringSerialization() throws Exception {
            ApiResponse<Void> response = ApiResponse.ok("삭제되었습니다.");

            JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(response));

            assertThat(json.get("success").asBoolean()).isTrue();
            assertThat(json.get("message").asText()).isEqualTo("삭제되었습니다.");
            assertThat(json.has("errorCode")).isFalse();
            assertThat(json.has("data")).isFalse();
        }

        @Test
        @DisplayName("fail_데이터없음_NULL데이터필드직렬화제외")
        void fail_withoutData_excludesOnlyNullDataFieldDuringSerialization() throws Exception {
            ApiResponse<Void> response = ApiResponse.fail("USER_NOT_FOUND", "사용자를 찾을 수 없습니다.");

            JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(response));

            assertThat(json.get("success").asBoolean()).isFalse();
            assertThat(json.get("errorCode").asText()).isEqualTo("USER_NOT_FOUND");
            assertThat(json.get("message").asText()).isEqualTo("사용자를 찾을 수 없습니다.");
            assertThat(json.has("data")).isFalse();
        }
    }
}
