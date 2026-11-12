package com.routinely.challenge_service;

import static org.assertj.core.api.Assertions.assertThat;

import com.routinely.web.filter.GatewayAuthFilter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

/**
 * 실제 서비스 컨텍스트가 common-web 자동설정으로 GatewayAuthFilter를 등록하지 않는지 확인한다 (#208).
 *
 * <p>설정이 같은 다른 테스트와 컨텍스트 캐시를 공유하므로 컨텍스트를 새로 띄우지 않는다.
 * 프로파일 순서가 {@code local, test}인 이유는 {@code GrpcReflectionLocalEnabledTest} 주석을 본다.
 */
@SpringBootTest
@ActiveProfiles({"local", "test"})
class GatewayAuthFilterLocalProfileTest {

    @Autowired
    private ApplicationContext context;

    @Test
    @DisplayName("local 프로파일에서는 GatewayAuthFilter가 등록되지 않는다")
    void gatewayAuthFilter_localProfile_notRegistered() {
        assertThat(context.getBeansOfType(GatewayAuthFilter.class)).isEmpty();
    }
}
