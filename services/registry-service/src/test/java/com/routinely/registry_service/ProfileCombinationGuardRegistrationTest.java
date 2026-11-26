package com.routinely.registry_service;

import static org.assertj.core.api.Assertions.assertThat;

import com.routinely.boot.profile.ProfileGuardProbe;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 이 서비스에 프로파일 조합 가드(common-boot)가 실려 있는지 확인한다(#215).
 *
 * <p>의존이나 등록이 빠지면 가드 대신 {@link ProfileGuardProbe.EnvironmentReady}가 나와 실패한다.
 * 환경 준비 단계에서 멈추므로 DB · Kafka · Eureka 없이 돈다.
 */
@DisplayName("RegistryServiceApplication 프로파일 가드 등록")
class ProfileCombinationGuardRegistrationTest {

    @ParameterizedTest(name = "프로파일 [{0}]")
    @ValueSource(strings = {"local,prod", "prod,local"})
    @DisplayName("local과 prod를 함께 켜면 가드가 기동을 막는다")
    void guard_localAndProd_blocksStartup(String profiles) {
        RuntimeException result = ProfileGuardProbe.run(RegistryServiceApplication.class, "--spring.profiles.active=" + profiles);

        assertThat(ProfileGuardProbe.blockedByGuard(result)).as(String.valueOf(result)).isTrue();
    }
}
