package com.routinely.web.openapi;

import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.ParseOptions;
import io.swagger.v3.parser.core.models.SwaggerParseResult;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 서비스 OpenAPI 명세의 계약 검사(#201) — 생성 · 유효성 · 정규화 · 기준 스냅샷 비교.
 *
 * <p>user · routine · challenge의 {@code OpenApiContractTest}가 공유한다. 스냅샷 위치와 갱신 모드는
 * Gradle이 시스템 속성으로 넘긴다(루트 {@code build.gradle}). 갱신:
 * {@code ./gradlew :services:{서비스}:test --tests '*OpenApiContractTest' -Popenapi.update=true}
 *
 * <h2>정규화 — 지우는 것과 남기는 것</h2>
 * <ul>
 *   <li>지운다: 최상위 {@code servers}(테스트마다 랜덤 포트가 들어간다. 대신 정규화 전에 {@code /}인지 따로 본다)</li>
 *   <li>정렬한다(<b>위치로 판단</b>): 객체 키 · 경로 · 오퍼레이션의 {@code parameters}({@code in}, {@code name} 순.
 *       {@code $ref} 항목은 ref 문자열) · 스키마의 {@code required} · 오퍼레이션의 {@code tags}</li>
 *   <li>손대지 않는다: 예시 데이터({@code example} · {@code examples} · {@code default} · {@code const}) 안의 배열</li>
 *   <li>원래 순서를 둔다: 최상위 {@code tags}(UI 섹션 순서) · {@code enum}(UI 표시 순서) · 그 밖의 배열</li>
 *   <li>그대로 남는다: 타입 · {@code format} · {@code required} · {@code security} · {@code operationId} · {@code info.version}</li>
 * </ul>
 */
public final class OpenApiContract {

    static final String SNAPSHOT_DIR_PROPERTY = "openapi.snapshot.dir";
    static final String UPDATE_PROPERTY = "openapi.snapshot.update";
    private static final String UPDATE_HINT =
            "의도한 변경이면 ./gradlew :services:%s:test --tests '*OpenApiContractTest' -Popenapi.update=true 로 스냅샷을 갱신하고 diff를 커밋한다.";

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private OpenApiContract() {
    }

    /** 실제 포트로 떠 있는 서비스에 GET 한다. {@code gatewaySecret}이 null이면 시크릿 헤더를 붙이지 않는다. */
    public static HttpResponse<String> get(int port, String path, String gatewaySecret) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET();
        if (gatewaySecret != null) {
            request.header("X-Gateway-Secret", gatewaySecret);
        }
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /**
     * 원본 명세를 검사하고 정규화본을 {@code build/openapi/{서비스}.json}에 쓴 뒤 기준 스냅샷과 비교한다.
     * 갱신 모드에서는 비교 대신 스냅샷을 덮어쓴다.
     */
    public static void verifyAgainstSnapshot(String serviceName, String rawJson) {
        JsonNode raw = MAPPER.readTree(rawJson);
        assertServersIsGatewayOrigin(raw);
        assertValidOpenApi("원본", rawJson);

        String normalized = normalize(raw);
        assertValidOpenApi("정규화본", normalized);
        Path generated = write(Path.of("build", "openapi", serviceName + ".json"), normalized);

        Path snapshot = snapshotDir().resolve(serviceName + ".json");
        if (Boolean.getBoolean(UPDATE_PROPERTY)) {
            write(snapshot, normalized);
            return;
        }
        if (!Files.exists(snapshot)) {
            throw new AssertionError("기준 스냅샷이 없다: " + snapshot + "\n" + UPDATE_HINT.formatted(serviceName));
        }
        String expected = read(snapshot).replace("\r\n", "\n");
        if (!expected.equals(normalized)) {
            throw new AssertionError("""
                    OpenAPI 명세가 기준 스냅샷과 다르다.
                      기준: %s
                      생성: %s
                    %s""".formatted(snapshot, generated.toAbsolutePath(), UPDATE_HINT.formatted(serviceName)));
        }
    }

    /** 신뢰 헤더({@code X-User-Id} · {@code X-Gateway-Secret})가 어느 오퍼레이션의 입력에도 없는지 본다. */
    public static void assertNoTrustedHeaderParameters(String rawJson) {
        List<String> found = new ArrayList<>();
        for (Map.Entry<String, JsonNode> path : MAPPER.readTree(rawJson).path("paths").properties()) {
            for (Map.Entry<String, JsonNode> operation : path.getValue().properties()) {
                for (JsonNode parameter : operation.getValue().path("parameters")) {
                    String name = parameter.path("name").asString();
                    if ("header".equals(parameter.path("in").asString())
                            && (name.equalsIgnoreCase("X-User-Id") || name.equalsIgnoreCase("X-Gateway-Secret"))) {
                        found.add(operation.getKey().toUpperCase() + " " + path.getKey() + " " + name);
                    }
                }
            }
        }
        if (!found.isEmpty()) {
            throw new AssertionError("신뢰 헤더가 명세 입력에 남아 있다: " + found);
        }
    }

    /**
     * 오퍼레이션의 실효 security를 돌려준다. 오퍼레이션에 {@code security}가 없으면 전역 값을 상속한다.
     *
     * @return 요구 스킴 이름 목록. 빈 목록은 {@code security: []}(공개)
     */
    public static List<String> effectiveSecurity(String rawJson, String method, String path) {
        JsonNode root = MAPPER.readTree(rawJson);
        JsonNode operation = root.path("paths").path(path).path(method.toLowerCase());
        if (operation.isMissingNode()) {
            throw new AssertionError("명세에 오퍼레이션이 없다: " + method + " " + path);
        }
        JsonNode security = operation.has("security") ? operation.get("security") : root.path("security");
        List<String> schemes = new ArrayList<>();
        security.forEach(requirement -> requirement.propertyNames().forEach(schemes::add));
        return schemes;
    }

    /** 오퍼레이션의 응답 상태 코드 목록. 생성 API가 201로 적혔는지 본다(springdoc은 ResponseEntity.status를 못 읽는다). */
    public static List<String> responseCodes(String rawJson, String method, String path) {
        List<String> codes = new ArrayList<>();
        operation(rawJson, method, path).path("responses").propertyNames().forEach(codes::add);
        return codes;
    }

    /** multipart 요청 본문 스키마의 필수 필드 목록. */
    public static List<String> multipartRequiredFields(String rawJson, String method, String path) {
        List<String> fields = new ArrayList<>();
        operation(rawJson, method, path).path("requestBody").path("content").path("multipart/form-data")
                .path("schema").path("required").forEach(field -> fields.add(field.asString()));
        return fields;
    }

    private static JsonNode operation(String rawJson, String method, String path) {
        JsonNode operation = MAPPER.readTree(rawJson).path("paths").path(path).path(method.toLowerCase());
        if (operation.isMissingNode()) {
            throw new AssertionError("명세에 오퍼레이션이 없다: " + method + " " + path);
        }
        return operation;
    }

    /** {@code servers}는 게이트웨이 origin 상대 경로 {@code /} 하나여야 한다. 스냅샷에서 지우므로 여기서 본다. */
    static void assertServersIsGatewayOrigin(JsonNode raw) {
        JsonNode servers = raw.path("servers");
        if (!servers.isArray() || servers.size() != 1 || !"/".equals(servers.get(0).path("url").asString())) {
            throw new AssertionError("servers는 [{url: \"/\"}] 하나여야 한다(게이트웨이 origin): " + servers);
        }
    }

    /** OpenAPI 3.1 파서로 읽어 문서가 만들어지고 진단 메시지가 없는지 본다. */
    static void assertValidOpenApi(String label, String json) {
        SwaggerParseResult result = new OpenAPIV3Parser().readContents(json, null, new ParseOptions());
        List<String> messages = result.getMessages() == null ? List.of() : result.getMessages();
        if (result.getOpenAPI() == null || !messages.isEmpty()) {
            throw new AssertionError("OpenAPI " + label + "이 유효하지 않다: " + messages);
        }
    }

    /** 정규화한 명세를 2칸 들여쓰기 JSON으로 돌려준다(끝 줄바꿈 1개, LF). */
    public static String normalize(JsonNode raw) {
        JsonNode normalized = normalizeNode(raw, List.of());
        return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(normalized)
                .replace("\r\n", "\n") + "\n";
    }

    /**
     * 정렬 여부는 배열 이름이 아니라 <b>OpenAPI 문서 안의 위치</b>로 정한다(#201 리뷰).
     * 예시 데이터({@code example} · {@code examples} · {@code default} · {@code const} · {@code enum}) 아래는 값이라 손대지 않는다.
     *
     * @param path 루트에서 이 노드까지의 키 목록
     */
    private static JsonNode normalizeNode(JsonNode node, List<String> path) {
        if (node.isObject()) {
            Map<String, JsonNode> sorted = new TreeMap<>();
            for (Map.Entry<String, JsonNode> entry : node.properties()) {
                if (path.isEmpty() && entry.getKey().equals("servers")) {
                    continue;
                }
                sorted.put(entry.getKey(), normalizeNode(entry.getValue(), append(path, entry.getKey())));
            }
            ObjectNode result = MAPPER.createObjectNode();
            sorted.forEach(result::set);
            return result;
        }
        if (node.isArray()) {
            List<JsonNode> items = new ArrayList<>();
            node.forEach(item -> items.add(normalizeNode(item, append(path, "[]"))));
            if (isParameterList(path)) {
                items.sort(Comparator.comparing(OpenApiContract::parameterSortKey));
            } else if ((isOperationTags(path) || isSchemaRequired(path)) && allText(items)) {
                items.sort(Comparator.comparing(JsonNode::asString));
            }
            ArrayNode result = MAPPER.createArrayNode();
            items.forEach(result::add);
            return result;
        }
        return node;
    }

    private static final Set<String> HTTP_METHODS =
            Set.of("get", "put", "post", "delete", "options", "head", "patch", "trace");
    private static final Set<String> DATA_KEYS =
            Set.of("example", "examples", "default", "const", "enum");

    /** {@code paths.{경로}.parameters} 또는 {@code paths.{경로}.{메서드}.parameters} */
    private static boolean isParameterList(List<String> path) {
        int n = path.size();
        if (n == 3 && path.get(0).equals("paths") && path.get(2).equals("parameters")) {
            return true;
        }
        return n == 4 && path.get(0).equals("paths") && HTTP_METHODS.contains(path.get(2))
                && path.get(3).equals("parameters");
    }

    /** {@code paths.{경로}.{메서드}.tags} — 최상위 {@code tags}(UI 섹션 순서)는 대상이 아니다 */
    private static boolean isOperationTags(List<String> path) {
        int n = path.size();
        return n == 4 && path.get(0).equals("paths") && HTTP_METHODS.contains(path.get(2))
                && path.get(3).equals("tags");
    }

    /** 스키마의 {@code required}(이름 목록). 예시 데이터 아래의 같은 이름 배열은 제외한다 */
    private static boolean isSchemaRequired(List<String> path) {
        if (path.isEmpty() || !path.get(path.size() - 1).equals("required")) {
            return false;
        }
        return path.stream().noneMatch(DATA_KEYS::contains)
                && (path.contains("schema") || path.contains("schemas") || path.contains("properties"));
    }

    private static List<String> append(List<String> path, String key) {
        List<String> next = new ArrayList<>(path);
        next.add(key);
        return List.copyOf(next);
    }

    private static String parameterSortKey(JsonNode parameter) {
        if (parameter.has("$ref")) {
            return "1|" + parameter.get("$ref").asString();
        }
        return "0|" + parameter.path("in").asString() + "|" + parameter.path("name").asString();
    }

    private static boolean allText(List<JsonNode> items) {
        return items.stream().allMatch(JsonNode::isString);
    }

    private static Path snapshotDir() {
        String dir = System.getProperty(SNAPSHOT_DIR_PROPERTY);
        if (dir == null || dir.isBlank()) {
            throw new IllegalStateException(SNAPSHOT_DIR_PROPERTY + " 시스템 속성이 없다. Gradle test 태스크로 실행한다(루트 build.gradle).");
        }
        return Path.of(dir);
    }

    private static Path write(Path path, String content) {
        try {
            Files.createDirectories(path.toAbsolutePath().getParent());
            return Files.writeString(path, content);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
