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
 * 실제 서비스 컨텍스트가 common-web 자동설정으로 GatewayAuthFilter를 등록하는지 확인한다 (#208).
 *
 * <p>설정이 같은 다른 테스트와 컨텍스트 캐시를 공유하므로 컨텍스트를 새로 띄우지 않는다.
 */
@SpringBootTest
@ActiveProfiles({"test"})
class GatewayAuthFilterRegistrationTest {

    @Autowired
    private ApplicationContext context;

    @Test
    @DisplayName("local이 아닌 프로파일에서는 GatewayAuthFilter가 등록된다")
    void gatewayAuthFilter_nonLocalProfile_registered() {
        assertThat(context.getBeansOfType(GatewayAuthFilter.class)).hasSize(1);
    }
}
