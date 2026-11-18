package com.routinely.challenge_service.presentation.rest;

import static org.assertj.core.api.Assertions.assertThat;

import com.routinely.web.openapi.OpenApiContract;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

/**
 * challenge-service의 OpenAPI 계약(#201) — 생성 · 유효성 · 기준 스냅샷 비교 · 신뢰 헤더 숨김.
 *
 * <p>명세는 기본으로 꺼져 있어(local만 켠다) 이 테스트에서만 켠다. 실제 포트로 띄워 GatewayAuthFilter까지 태운다.
 * 스냅샷: {@code docs/api/openapi/challenge-service.json}
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "springdoc.api-docs.enabled=true")
@ActiveProfiles("test")
@DisplayName("OpenAPI 계약")
class OpenApiContractTest {

    @LocalServerPort
    private int port;

    @Value("${gateway.secret}")
    private String gatewaySecret;

    @Test
    @DisplayName("명세가 생성되고 유효하며 기준 스냅샷과 같다")
    void apiDocs_matchesSnapshot() {
        OpenApiContract.verifyAgainstSnapshot("challenge-service", apiDocs());
    }

    @Test
    @DisplayName("X-User-Id · X-Gateway-Secret은 명세 입력에서 빠진다")
    void apiDocs_hidesTrustedHeaders() {
        OpenApiContract.assertNoTrustedHeaderParameters(apiDocs());
    }

    @Test
    @DisplayName("명세 경로도 Gateway 시크릿이 없으면 401이다")
    void apiDocs_withoutGatewaySecret_unauthorized() {
        assertThat(OpenApiContract.get(port, "/v3/api-docs", null).statusCode()).isEqualTo(401);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"POST /api/v1/challenges", "POST /api/v1/challenges/{challengeId}/members"})
    @DisplayName("생성 API는 명세에 201로 적힌다")
    void apiDocs_createEndpoints_documented201(String endpoint) {
        String[] methodAndPath = endpoint.split(" ");
        assertThat(OpenApiContract.responseCodes(apiDocs(), methodAndPath[0], methodAndPath[1])).containsExactly("201");
    }

    @Test
    @DisplayName("이미지 업로드의 image 파트는 명세에서 필수다(동작은 EMPTY_FILE 400으로 거절)")
    void apiDocs_imageUpload_imageRequired() {
        assertThat(OpenApiContract.multipartRequiredFields(apiDocs(), "PUT", "/api/v1/challenges/{challengeId}/image")).containsExactly("image");
    }

    private String apiDocs() {
        HttpResponse<String> response = OpenApiContract.get(port, "/v3/api-docs", gatewaySecret);
        assertThat(response.statusCode()).isEqualTo(200);
        return response.body();
    }
}
