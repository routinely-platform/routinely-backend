package com.routinely.routine_service.presentation.grpc;

import static org.assertj.core.api.Assertions.assertThat;

import io.grpc.BindableService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

/**
 * local 프로파일에서 gRPC 서버 reflection이 켜지는지 검증한다 (#199).
 *
 * <p><b>프로파일 순서는 {@code local, test}여야 한다.</b> 같은 키는 뒤에 적은 프로파일이 이긴다.
 * {@code test, local}로 두면 local의 datasource({@code localhost:5432})가 test의 Testcontainers(jdbc:tc:postgresql)를 덮어,
 * 로컬 DB가 없는 환경에서 컨텍스트 로드가 실패한다.
 * {@code local, test}면 test가 datasource를 되찾고, local은 test yaml에 없는 키만 남긴다 —
 * 그중 하나가 {@code spring.grpc.server.reflection.enabled: true}다.
 */
@SpringBootTest
@ActiveProfiles({"local", "test"})
class GrpcReflectionLocalEnabledTest {

    private static final String REFLECTION_BEAN_NAME = "serverReflection";

    @Autowired
    private ApplicationContext context;

    @Test
    @DisplayName("local 프로파일에서는 gRPC 서버 reflection 빈이 등록된다")
    void reflection_localProfile_registered() {
        assertThat(context.containsBean(REFLECTION_BEAN_NAME)).isTrue();
        assertThat(context.getBean(REFLECTION_BEAN_NAME)).isInstanceOf(BindableService.class);
    }
}
