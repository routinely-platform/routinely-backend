package com.routinely.challenge_service.presentation.grpc;

import static org.assertj.core.api.Assertions.assertThat;

import io.grpc.BindableService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

/**
 * gRPC 서버 reflection이 기본으로 꺼져 있는지 검증한다 (#199).
 *
 * <p>{@code application.yaml}의 {@code spring.grpc.server.reflection.enabled: false}가 기본값이고,
 * {@code application-test.yaml}은 이 키를 덮지 않는다. 따라서 local 이외의 프로파일(prod 포함)과 같은 상태다.
 * reflection이 켜지면 자동설정이 {@link BindableService} 타입의 {@code serverReflection} 빈을 등록한다.
 */
@SpringBootTest
@ActiveProfiles("test")
class GrpcReflectionDefaultDisabledTest {

    private static final String REFLECTION_BEAN_NAME = "serverReflection";

    @Autowired
    private ApplicationContext context;

    @Test
    @DisplayName("기본 설정에서는 gRPC 서버 reflection 빈이 등록되지 않는다")
    void reflection_defaultProfile_notRegistered() {
        assertThat(context.containsBean(REFLECTION_BEAN_NAME)).isFalse();
    }
}
