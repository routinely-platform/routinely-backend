package com.routinely.web.filter;

import com.routinely.core.constant.HeaderConstants;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 실제 Tomcat이 해석한 servletPath · pathInfo로 {@code /actuator} 제외가 판정되는지 검증한다 (#208 리뷰).
 *
 * <p>MockMvc는 컨테이너의 경로 디코딩 · 정규화를 거치지 않는다. 그래서 내장 Tomcat을 띄우고,
 * HTTP 클라이언트가 경로를 정규화하지 못하게 소켓으로 요청 줄을 그대로 보낸다.
 */
@DisplayName("GatewayAuthFilter — 실제 Tomcat 경로 처리")
@SpringBootTest(classes = GatewayAuthFilterTomcatPathTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "gateway.secret=" + GatewayAuthFilterTomcatPathTest.SECRET)
@ActiveProfiles("test")
class GatewayAuthFilterTomcatPathTest {

    static final String SECRET = "tomcat-secret";
    private static final String PROTECTED_BODY = "pong";

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
            return PROTECTED_BODY;
        }

        @GetMapping("/actuator/prometheus")
        String prometheus() {
            return "metrics";
        }
    }

    @LocalServerPort
    int port;

    @Test
    @DisplayName("시크릿이 맞으면 보호 경로에 도달한다 — 아래 우회 테스트의 대조군")
    void protectedPath_withSecret_reachesController() throws IOException {
        RawResponse response = get("/api/v1/ping", true);

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body()).contains(PROTECTED_BODY);
    }

    @Test
    @DisplayName("시크릿 없이 /actuator/prometheus는 통과한다")
    void actuator_withoutSecret_passes() throws IOException {
        RawResponse response = get("/actuator/prometheus", false);

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body()).contains("metrics");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "/api/v1/ping",
            "/actuator/../api/v1/ping",
            "/actuator/%2e%2e/api/v1/ping",
            "/actuator/%2E%2E/api/v1/ping",
            "/actuator/..;/api/v1/ping",
            "/actuator;/../api/v1/ping",
            "/actuator;x=1/../api/v1/ping",
            "/actuator%2f..%2fapi/v1/ping",
            "/actuator/%2e%2e%2fapi/v1/ping",
            "//actuator/../api/v1/ping",
            "/actuator//../api/v1/ping",
            "/ACTUATOR/../api/v1/ping"
    })
    @DisplayName("시크릿 없이 /actuator 접두사를 꾸며 보호 경로에 닿을 수 없다")
    void protectedPath_disguisedAsActuator_neverReachesController(String rawPath) throws IOException {
        RawResponse response = get(rawPath, false);

        // 400은 Tomcat이 요청 줄을 거부한 것(인코딩된 슬래시), 401은 이 필터가 막은 것이다.
        // "200만 아니면 된다"로 두면 회귀를 놓친다 — 원시 URI로 판정하게 바꾸면 필터를 건너뛰고
        // Spring MVC가 정규화되지 않은 경로에서 500을 내 컨트롤러에 우연히 닿지 않는다. 그 500도 실패로 잡는다.
        assertThat(response.status()).isIn(400, 401);
        assertThat(response.body()).doesNotContain(PROTECTED_BODY);
    }

    private RawResponse get(String rawPath, boolean withSecret) throws IOException {
        try (Socket socket = new Socket("localhost", port)) {
            socket.setSoTimeout(5_000);
            String request = "GET " + rawPath + " HTTP/1.1\r\n"
                    + "Host: localhost:" + port + "\r\n"
                    + (withSecret ? HeaderConstants.GATEWAY_SECRET + ": " + SECRET + "\r\n" : "")
                    + "Connection: close\r\n\r\n";
            OutputStream out = socket.getOutputStream();
            out.write(request.getBytes(StandardCharsets.US_ASCII));
            out.flush();

            InputStream in = socket.getInputStream();
            String raw = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            int status = Integer.parseInt(raw.substring(9, 12));   // "HTTP/1.1 200 ..."
            int bodyStart = raw.indexOf("\r\n\r\n");
            return new RawResponse(status, bodyStart < 0 ? "" : raw.substring(bodyStart + 4));
        }
    }

    private record RawResponse(int status, String body) {
    }
}
