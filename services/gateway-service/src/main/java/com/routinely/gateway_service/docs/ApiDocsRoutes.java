package com.routinely.gateway_service.docs;

import java.util.List;

/**
 * 서비스 OpenAPI 명세를 게이트웨이로 가져오는 문서 경로(#201).
 *
 * <p>{@code GET /__docs/{서비스}} → {@code lb://{서비스}/v3/api-docs}. 라우트는 local에서만 등록되고
 * ({@link ApiDocsRouteConfig}), {@code JwtAuthenticationFilter}는 이 경로를 GET에 한해 공개 경로로 본다.
 * 경로를 {@code /v3/api-docs/{name}}으로 두지 않는 이유: 게이트웨이 springdoc이 {@code /v3/api-docs/**}를 직접 서빙해
 * 그룹 설정이 생기면 라우트보다 먼저 잡는다.
 */
public final class ApiDocsRoutes {

    public static final String PATH_PREFIX = "/__docs/";
    public static final String UPSTREAM_PATH = "/v3/api-docs";

    /** REST 컨트롤러가 있는 서비스. 게이트웨이 local 설정의 {@code springdoc.swagger-ui.urls}와 같아야 한다 */
    public static final List<String> SERVICES = List.of("user-service", "routine-service", "challenge-service");

    private ApiDocsRoutes() {
    }

    public static List<String> paths() {
        return SERVICES.stream().map(service -> PATH_PREFIX + service).toList();
    }
}
