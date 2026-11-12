package com.routinely.web.filter;

import com.routinely.core.constant.HeaderConstants;
import com.routinely.core.exception.ErrorCode;
import com.routinely.core.response.ApiResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Gateway를 거친 요청인지 {@code X-Gateway-Secret} 헤더로 확인한다 (ADR-0019 2차 방어).
 *
 * <p><b>등록 경로</b> — 컴포넌트 스캔이 아니라 {@code WebAutoConfiguration}의 {@code @Import}로 등록된다.
 * 이 패키지({@code com.routinely.web})는 서비스의 스캔 범위({@code com.routinely.{svc}_service}) 밖이라
 * {@code @Component}를 붙여도 등록되지 않는다.
 *
 * <p><b>local</b> — Gateway의 {@code JwtAuthenticationFilter}도 local에서는 꺼져 시크릿을 붙이지 않으므로
 * 이 필터도 등록하지 않는다. local 정책은 #201에서 두 필터를 함께 정한다.
 *
 * <p><b>순서</b> — {@code @Order}를 두지 않아 가장 나중에 실행된다. Boot의 관측 필터가 먼저 돌아
 * 401 응답에도 traceId · 메트릭이 남는다.
 */
@Profile("!local")
public class GatewayAuthFilter extends OncePerRequestFilter {

    private static final String ACTUATOR_PATH = "/actuator";

    private final String gatewaySecret;
    private final ObjectMapper objectMapper;

    public GatewayAuthFilter(
            @Value("${gateway.secret}") String gatewaySecret,
            ObjectMapper objectMapper) {
        // 빈 값이면 빈 헤더를 단 요청이 통과한다. 키가 없을 때처럼 기동을 실패시킨다.
        Assert.hasText(gatewaySecret, "gateway.secret이 비어 있습니다. GATEWAY_SECRET 환경변수를 설정하세요.");
        this.gatewaySecret = gatewaySecret;
        this.objectMapper = objectMapper;
    }

    /**
     * Prometheus는 서비스의 {@code /actuator/prometheus}를 헤더 없이 직접 수집한다.
     * management 포트를 분리하면(#23) 이 제외를 지운다.
     *
     * <p>경로는 서블릿 컨테이너가 디코딩한 servletPath + pathInfo를 다시 정규화해 판정한다.
     * {@code /actuator/../api/...}처럼 접두사만 맞춘 경로가 제외 규칙을 타지 못하게 하기 위해서다.
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();
        if (request.getPathInfo() != null) {
            path += request.getPathInfo();
        }
        String normalized = StringUtils.cleanPath(path);
        return normalized.equals(ACTUATOR_PATH) || normalized.startsWith(ACTUATOR_PATH + "/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String secret = request.getHeader(HeaderConstants.GATEWAY_SECRET);
        if (!gatewaySecret.equals(secret)) {
            sendUnauthorized(response);
            return;
        }
        filterChain.doFilter(request, response);
    }

    private void sendUnauthorized(HttpServletResponse response) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        String body = objectMapper.writeValueAsString(
                ApiResponse.fail(ErrorCode.UNAUTHORIZED.getCode(), ErrorCode.UNAUTHORIZED.getMessage())
        );
        response.getWriter().write(body);
    }
}
