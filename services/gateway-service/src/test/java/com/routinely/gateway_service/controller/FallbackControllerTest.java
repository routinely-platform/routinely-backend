package com.routinely.gateway_service.controller;

import com.routinely.core.response.ApiResponse;
import java.net.URI;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
@DisplayName("FallbackController")
class FallbackControllerTest {

    private FallbackController controller;

    @BeforeEach
    void setUp() {
        controller = new FallbackController();
    }

    @Test
    @DisplayName("fallback는 원래 요청 URI와 예외 원인을 로그에 남긴다")
    void fallback_logsOriginalRequestUriAndCause(CapturedOutput output) {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/fallback/user-service"));
        exchange.getAttributes().put(
                ServerWebExchangeUtils.GATEWAY_ORIGINAL_REQUEST_URL_ATTR,
                Set.of(URI.create("http://localhost:8080/api/v1/users/me")));
        exchange.getAttributes().put(
                ServerWebExchangeUtils.CIRCUITBREAKER_EXECUTION_EXCEPTION_ATTR,
                new RuntimeException("upstream timeout"));

        ResponseEntity<?> response = controller.fallback("user-service", exchange).block();

        assertThat(response).isNotNull();
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(output.getOut())
                .contains("originalUri=http://localhost:8080/api/v1/users/me")
                .contains("fallbackPath=/fallback/user-service")
                .contains("cause=RuntimeException: upstream timeout");
    }

    @Test
    @DisplayName("fallback_응답본문_success필드false")
    void fallback_responseBody_successIsFalse() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/users/me"));

        ResponseEntity<ApiResponse<Void>> response =
                controller.fallback("user-service", exchange).block();

        assertThat(response.getBody().isSuccess()).isFalse();
    }

    @Test
    @DisplayName("fallback_응답본문_SERVICE_UNAVAILABLE에러코드")
    void fallback_responseBody_containsServiceUnavailableErrorCode() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/challenges/1"));

        ResponseEntity<ApiResponse<Void>> response =
                controller.fallback("challenge-service", exchange).block();

        assertThat(response.getBody().getErrorCode()).isEqualTo("SERVICE_UNAVAILABLE");
    }

    @Test
    @DisplayName("fallback_응답본문_message필드존재")
    void fallback_responseBody_containsMessage() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/routines/1"));

        ResponseEntity<ApiResponse<Void>> response =
                controller.fallback("routine-service", exchange).block();

        assertThat(response.getBody().getMessage()).isNotBlank();
    }
}
