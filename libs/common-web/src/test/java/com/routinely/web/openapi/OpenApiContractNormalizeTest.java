package com.routinely.web.openapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@DisplayName("OpenApiContract 정규화 · 검증")
class OpenApiContractNormalizeTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private static final String SPEC = """
            {
              "openapi": "3.1.0",
              "servers": [{"url": "http://localhost:51234"}],
              "info": {"version": "v1", "title": "t"},
              "tags": [{"name": "z"}, {"name": "a"}],
              "paths": {
                "/b": {"get": {
                  "tags": ["z-tag", "a-tag"],
                  "parameters": [
                    {"name": "size", "in": "query"},
                    {"$ref": "#/components/parameters/P"},
                    {"name": "id", "in": "path", "required": true}
                  ],
                  "responses": {"200": {"description": "OK"}}
                }},
                "/a": {"post": {"security": [], "responses": {"200": {"description": "OK"}}}}
              },
              "components": {"schemas": {"S": {
                "type": "object",
                "required": ["b", "a"],
                "properties": {"status": {"type": "string", "enum": ["READY", "DONE", "ACTIVE"]}},
                "example": {"tags": ["z", "a"], "required": ["y", "x"], "parameters": ["q", "p"]}
              }}}
            }
            """;

    @Test
    @DisplayName("servers를 지우고 키 · parameters · required · 오퍼레이션 tags를 정렬하며 최상위 tags · enum · security · 예시 데이터는 둔다")
    void normalize_appliesRules() {
        JsonNode normalized = MAPPER.readTree(OpenApiContract.normalize(MAPPER.readTree(SPEC)));

        assertThat(normalized.has("servers")).isFalse();
        assertThat(normalized.propertyNames()).containsExactly("components", "info", "openapi", "paths", "tags");
        assertThat(normalized.path("paths").propertyNames()).containsExactly("/a", "/b");

        JsonNode get = normalized.path("paths").path("/b").path("get");
        assertThat(get.path("parameters")).extracting(p -> p.has("$ref") ? "ref" : p.path("in").asString() + ":" + p.path("name").asString())
                .containsExactly("path:id", "query:size", "ref");
        assertThat(get.path("tags")).extracting(JsonNode::asString).containsExactly("a-tag", "z-tag");

        assertThat(normalized.path("tags")).extracting(t -> t.path("name").asString()).containsExactly("z", "a");
        JsonNode schema = normalized.path("components").path("schemas").path("S");
        assertThat(schema.path("required")).extracting(JsonNode::asString).containsExactly("a", "b");
        assertThat(schema.path("properties").path("status").path("enum")).extracting(JsonNode::asString)
                .containsExactly("READY", "DONE", "ACTIVE");
        JsonNode example = schema.path("example");
        assertThat(example.path("tags")).extracting(JsonNode::asString).containsExactly("z", "a");
        assertThat(example.path("required")).extracting(JsonNode::asString).containsExactly("y", "x");
        assertThat(example.path("parameters")).extracting(JsonNode::asString).containsExactly("q", "p");
        assertThat(normalized.path("paths").path("/a").path("post").path("security").isArray()).isTrue();
        assertThat(normalized.path("paths").path("/a").path("post").path("security")).isEmpty();
    }

    @Test
    @DisplayName("정규화 결과는 같은 입력에서 항상 같고 LF 한 줄로 끝난다")
    void normalize_isStable() {
        String first = OpenApiContract.normalize(MAPPER.readTree(SPEC));
        String second = OpenApiContract.normalize(MAPPER.readTree(first));

        assertThat(second).isEqualTo(first);
        assertThat(first).endsWith("}\n").doesNotContain("\r");
    }

    @Test
    @DisplayName("servers가 / 하나가 아니면 실패한다")
    void assertServers_notGatewayOrigin_fails() {
        assertThatThrownBy(() -> OpenApiContract.assertServersIsGatewayOrigin(MAPPER.readTree(SPEC)))
                .isInstanceOf(AssertionError.class).hasMessageContaining("servers");
    }

    @Test
    @DisplayName("OpenAPI 문서가 아니면 유효성 검사가 실패한다")
    void assertValid_invalidDocument_fails() {
        assertThatThrownBy(() -> OpenApiContract.assertValidOpenApi("원본", "{\"openapi\":\"3.1.0\",\"paths\":{\"/x\":{\"get\":{}}}}"))
                .isInstanceOf(AssertionError.class).hasMessageContaining("유효하지 않다");
    }
}
