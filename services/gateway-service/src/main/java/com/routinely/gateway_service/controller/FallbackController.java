package com.routinely.gateway_service.controller;

import com.routinely.core.exception.ErrorCode;
import com.routinely.core.response.ApiResponse;
import java.net.URI;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Slf4j
@RestController
public class FallbackController {

    @RequestMapping(value = "/fallback/{serviceName}", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<ApiResponse<Void>>> fallback(
            @PathVariable String serviceName,
            ServerWebExchange exchange) {

        log.warn("[CircuitBreaker] {} is unavailable. originalUri={}, fallbackPath={}, cause={}",
                serviceName,
                resolveOriginalRequestUri(exchange),
                exchange.getRequest().getPath().value(),
                resolveFailureCause(exchange));

        return Mono.just(ResponseEntity
                .status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ApiResponse.fail(
                        ErrorCode.SERVICE_UNAVAILABLE.getCode(),
                        ErrorCode.SERVICE_UNAVAILABLE.getMessage()
                )));
    }

    private String resolveOriginalRequestUri(ServerWebExchange exchange) {
        Set<URI> originalUris = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ORIGINAL_REQUEST_URL_ATTR);
        if (originalUris == null || originalUris.isEmpty()) {
            return exchange.getRequest().getURI().toString();
        }

        return originalUris.stream()
                .findFirst()
                .map(URI::toString)
                .orElse(exchange.getRequest().getURI().toString());
    }

    private String resolveFailureCause(ServerWebExchange exchange) {
        Throwable throwable = exchange.getAttribute(ServerWebExchangeUtils.CIRCUITBREAKER_EXECUTION_EXCEPTION_ATTR);
        if (throwable == null) {
            return "unknown";
        }

        String message = throwable.getMessage();
        if (message == null || message.isBlank()) {
            return throwable.getClass().getSimpleName();
        }

        return throwable.getClass().getSimpleName() + ": " + message;
    }
}
