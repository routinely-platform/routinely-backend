package com.routinely.gateway_service.docs;

import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpMethod;

/**
 * local 전용 문서 라우트(#201) — {@code GET /__docs/{서비스}} → {@code lb://{서비스}/v3/api-docs}.
 *
 * <p>yaml이 아니라 Java로 두는 이유: 라우트는 {@code application.yaml}의 YAML 리스트라 프로파일 yaml에 적으면
 * 병합되지 않고 리스트 전체가 교체된다. prod에서는 빈 자체가 없어 경로가 열리지 않는다.
 *
 * <p>글로벌 필터는 그대로 탄다 — 신뢰 헤더 정화 → JWT(GET만 공개 경로, Gateway 시크릿 주입) → Rate Limit(IP 키).
 */
@Configuration
@Profile("local")
public class ApiDocsRouteConfig {

    @Bean
    public RouteLocator apiDocsRoutes(RouteLocatorBuilder builder) {
        RouteLocatorBuilder.Builder routes = builder.routes();
        for (String service : ApiDocsRoutes.SERVICES) {
            routes.route("api-docs-" + service, route -> route
                    .method(HttpMethod.GET)
                    .and().path(ApiDocsRoutes.PATH_PREFIX + service)
                    .filters(filter -> filter.setPath(ApiDocsRoutes.UPSTREAM_PATH))
                    .uri("lb://" + service));
        }
        return routes.build();
    }
}
