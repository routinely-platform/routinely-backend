package com.routinely.boot.profile;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.Configuration;

/**
 * 프로파일 조합 가드(#215). {@code ApplicationContextRunner}는 EnvironmentPostProcessor를 돌리지 않으므로
 * {@link ProfileGuardProbe}로 실제 {@code SpringApplication}의 환경 준비 단계를 실행한다.
 */
@DisplayName("ProfileCombinationGuard")
class ProfileCombinationGuardTest {

    @ParameterizedTest(name = "프로파일 [{0}]")
    @ValueSource(strings = {"local,prod", "prod,local", "local,prod,observability", "observability,prod,local",
            "local,prod,test", "test,prod,local"})
    @DisplayName("커맨드라인으로 local과 prod를 함께 켜면 순서와 애드온에 상관없이 기동이 실패한다")
    void guard_localAndProdByArgs_fails(String profiles) {
        RuntimeException result = ProfileGuardProbe.run(Probe.class, "--spring.profiles.active=" + profiles);

        assertThat(ProfileGuardProbe.blockedByGuard(result)).as(String.valueOf(result)).isTrue();
        assertThat(result).hasMessageContaining("충돌=[");
    }

    @ParameterizedTest(name = "SPRING_PROFILES_ACTIVE=[{0}]")
    @ValueSource(strings = {"local,prod", "prod,local"})
    @DisplayName("환경변수로 local과 prod를 함께 켜도 기동이 실패한다")
    void guard_localAndProdByEnv_fails(String profiles) {
        RuntimeException result = ProfileGuardProbe.runWithEnv(Probe.class,
                Map.of("SPRING_PROFILES_ACTIVE", profiles));

        assertThat(ProfileGuardProbe.blockedByGuard(result)).as(String.valueOf(result)).isTrue();
    }

    @Test
    @DisplayName("yaml의 spring.profiles.active로 local과 prod를 함께 켜도 기동이 실패한다")
    void guard_localAndProdByYaml_fails() {
        RuntimeException result = ProfileGuardProbe.run(Probe.class,
                "--spring.config.location=classpath:profile-guard/local-prod.yaml");

        assertThat(ProfileGuardProbe.blockedByGuard(result)).as(String.valueOf(result)).isTrue();
    }

    @Test
    @DisplayName("활성 프로파일 없이 기본 프로파일로 local과 prod가 함께 적용돼도 기동이 실패한다")
    void guard_localAndProdByDefaultProfiles_fails() {
        RuntimeException result = ProfileGuardProbe.run(Probe.class, "--spring.profiles.default=local,prod");

        assertThat(ProfileGuardProbe.blockedByGuard(result)).as(String.valueOf(result)).isTrue();
    }

    @Test
    @DisplayName("활성 프로파일이 있으면 기본 프로파일은 적용되지 않으므로 보지 않는다")
    void guard_activeOverridesDefault_passes() {
        RuntimeException result = ProfileGuardProbe.run(Probe.class,
                "--spring.profiles.active=prod", "--spring.profiles.default=local,prod");

        assertThat(result).isInstanceOf(ProfileGuardProbe.EnvironmentReady.class);
    }

    @ParameterizedTest(name = "프로파일 [{0}]")
    @ValueSource(strings = {"", "local", "prod", "local,observability", "prod,observability",
            "local,test", "test,local", "prod,test", "test"})
    @DisplayName("환경 프로파일이 하나 이하인 조합은 가드가 허용한다")
    void guard_singleEnvironment_passes(String profiles) {
        RuntimeException result = profiles.isEmpty()
                ? ProfileGuardProbe.run(Probe.class)
                : ProfileGuardProbe.run(Probe.class, "--spring.profiles.active=" + profiles);

        assertThat(result).isInstanceOf(ProfileGuardProbe.EnvironmentReady.class);
    }

    @Test
    @DisplayName("spring.profiles.include로 local이 더해져도 기동이 실패한다")
    void guard_localByInclude_fails() {
        RuntimeException result = ProfileGuardProbe.run(Probe.class,
                "--spring.profiles.active=prod", "--spring.profiles.include=local");

        assertThat(ProfileGuardProbe.blockedByGuard(result)).as(String.valueOf(result)).isTrue();
    }

    @Test
    @DisplayName("프로파일 그룹 하나가 local과 prod로 펼쳐져도 기동이 실패한다")
    void guard_localAndProdByGroup_fails() {
        RuntimeException result = ProfileGuardProbe.run(Probe.class,
                "--spring.profiles.group.everything=local,prod", "--spring.profiles.active=everything");

        assertThat(ProfileGuardProbe.blockedByGuard(result)).as(String.valueOf(result)).isTrue();
    }

    @Test
    @DisplayName("실패 메시지에는 충돌한 환경 프로파일만 남고 다른 프로파일 이름(설정값 참조 결과)은 남지 않는다")
    void guard_message_containsOnlyConflictingEnvironments() {
        RuntimeException result = ProfileGuardProbe.run(Probe.class,
                "--review.sample=sample-secret-value",
                "--spring.profiles.active=local,prod,${review.sample}");

        assertThat(ProfileGuardProbe.blockedByGuard(result)).as(String.valueOf(result)).isTrue();
        assertThat(result.getMessage())
                .contains("충돌=[local, prod]")
                .doesNotContain("sample-secret-value");
    }

    @ParameterizedTest(name = "시스템 속성 {0}")
    @CsvSource(delimiter = '|', value = {
            "spring.profiles.active | prod",
            "spring.application.json | {\"spring\":{\"profiles\":{\"active\":\"prod\"}}}"})
    @DisplayName("테스트 JVM의 시스템 속성에 프로파일이 있어도 프로브는 그 값을 보지 않는다")
    void probe_ignoresSystemProfileInputs(String key, String value) {
        System.setProperty(key, value);
        try {
            RuntimeException result = ProfileGuardProbe.run(Probe.class,
                    "--spring.config.location=classpath:profile-guard/local-prod.yaml");

            assertThat(ProfileGuardProbe.blockedByGuard(result)).as(String.valueOf(result)).isTrue();
        } finally {
            System.clearProperty(key);
        }
    }

    @Test
    @DisplayName("프로브가 가드를 알아보는 메시지 접두사가 가드의 실제 메시지와 같다")
    void probePrefix_matchesGuardMessage() {
        assertThat(ProfileGuardProbe.GUARD_MESSAGE_PREFIX).isEqualTo(ProfileCombinationGuard.MESSAGE_PREFIX);
    }

    @Configuration(proxyBeanMethods = false)
    static class Probe {
    }
}
