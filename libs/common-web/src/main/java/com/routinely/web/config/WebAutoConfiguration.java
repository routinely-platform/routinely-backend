package com.routinely.web.config;

import com.routinely.web.filter.GatewayAuthFilter;
import com.routinely.web.handler.GlobalExceptionHandler;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Import;

/**
 * common-web이 서비스에 등록하는 빈의 목록.
 *
 * <p>서비스의 컴포넌트 스캔은 {@code com.routinely.{svc}_service}에서 시작하므로 이 모듈의 클래스를 찾지 못한다.
 * 이 클래스는 {@code META-INF/spring/...AutoConfiguration.imports}에 적혀 있어 자동설정으로 로드되고,
 * 여기서 {@code @Import}한 빈만 서비스에 등록된다. 새 공통 빈을 추가할 때는 여기에 적는다.
 *
 * <p>{@link GatewayAuthFilter}는 자체 {@code @Profile("!local")}로 local에서 빠진다.
 */
@AutoConfiguration
@Import({GlobalExceptionHandler.class, GatewayAuthFilter.class})
public class WebAutoConfiguration {
}
