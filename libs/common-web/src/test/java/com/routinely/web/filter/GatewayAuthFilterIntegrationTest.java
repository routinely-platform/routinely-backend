package com.routinely.web.filter;

import com.routinely.core.constant.HeaderConstants;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 필터를 직접 만들지 않고, 자동설정이 등록한 필터가 실제 요청 경로에 걸리는지 검증한다 (#208).
 *
 * <p>{@code GatewayAuthFilterTest}는 필터를 {@code new}로 만들어 로직만 본다. 그 방식으로는
 * "필터가 등록되지 않았다"를 잡지 못했기 때문에, 여기서는 서비스와 같은 자동설정 경로를 탄다.
 */
@DisplayName("GatewayAuthFilter — 자동설정 등록 후 요청 경로")
@SpringBootTest(classes = GatewayAuthFilterIntegrationTest.TestApplication.class,
        properties = "gateway.secret=" + GatewayAuthFilterIntegrationTest.SECRET)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class GatewayAuthFilterIntegrationTest {

    static final String SECRET = "integration-secret";

    // @SpringBootConfiguration을 쓰지 않는다 — 같은 패키지의 @WebMvcTest가 이 클래스를 설정으로 집지 않게.
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @Import(TestController.class)
    static class TestApplication {
    }

    @RestController
    static class TestController {
        @GetMapping("/api/v1/ping")
        String ping() {
            return "pong";
        }

        @GetMapping("/actuator/prometheus")
        String prometheus() {
            return "metrics";
        }
    }

    @Autowired
    MockMvc mockMvc;

    @Test
    @DisplayName("시크릿 헤더가 없으면 401을 반환한다")
    void request_withoutSecret_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/ping").header(HeaderConstants.USER_ID, "42"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("UNAUTHORIZED"));
    }

    @Test
    @DisplayName("시크릿이 틀리면 401을 반환한다")
    void request_withWrongSecret_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/ping").header(HeaderConstants.GATEWAY_SECRET, "wrong"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("시크릿이 맞으면 컨트롤러까지 도달한다")
    void request_withValidSecret_passes() throws Exception {
        mockMvc.perform(get("/api/v1/ping").header(HeaderConstants.GATEWAY_SECRET, SECRET))
                .andExpect(status().isOk())
                .andExpect(content().string("pong"));
    }

    @Test
    @DisplayName("/actuator 경로는 시크릿 없이도 통과한다 (Prometheus 직접 수집)")
    void request_actuatorWithoutSecret_passes() throws Exception {
        mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andExpect(content().string("metrics"));
    }
}
