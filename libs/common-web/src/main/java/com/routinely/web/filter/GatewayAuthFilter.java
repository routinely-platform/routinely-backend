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
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

// local 프로파일에서는 Gateway 없이 직접 서비스를 호출하므로 필터 비활성화
@Component
@Profile("!local")
public class GatewayAuthFilter extends OncePerRequestFilter {

    private final String gatewaySecret;
    private final ObjectMapper objectMapper;

    public GatewayAuthFilter(
            @Value("${gateway.secret}") String gatewaySecret,
            ObjectMapper objectMapper) {
        this.gatewaySecret = gatewaySecret;
        this.objectMapper = objectMapper;
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
