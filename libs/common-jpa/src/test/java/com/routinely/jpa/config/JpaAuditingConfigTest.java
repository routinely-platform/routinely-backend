package com.routinely.jpa.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.data.auditing.DateTimeProvider;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.TemporalAccessor;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("JpaAuditingConfig")
class JpaAuditingConfigTest {

    private static final Instant NANO_INSTANT = Instant.parse("2026-11-13T05:58:44.740903532Z");

    @Test
    @DisplayName("AutoConfiguration어노테이션선언_부트스프링자동등록대상")
    void isMarkedAsAutoConfiguration() {
        assertThat(JpaAuditingConfig.class.isAnnotationPresent(AutoConfiguration.class)).isTrue();
    }

    @Test
    @DisplayName("EnableJpaAuditing어노테이션선언_감사기능활성화 — 시각 공급자를 지정한다")
    void enablesJpaAuditing() {
        EnableJpaAuditing auditing = JpaAuditingConfig.class.getAnnotation(EnableJpaAuditing.class);

        assertThat(auditing).isNotNull();
        assertThat(auditing.dateTimeProviderRef()).isEqualTo(JpaAuditingConfig.DATE_TIME_PROVIDER);
    }

    @Test
    @DisplayName("서비스 Clock의 나노초 시각을 마이크로초로 버린다 — 시간대는 JVM 기본을 쓴다")
    void provider_serviceClock_truncatesToMicrosInJvmZone() {
        // 서비스 Clock 시간대를 일부러 JVM 기본과 다르게 둔다 — 시간대는 Clock이 아니라 JVM 기본을 따라야 한다
        ZoneId clockZone = ZoneId.systemDefault().getId().equals("Pacific/Kiritimati")
                ? ZoneId.of("Pacific/Pago_Pago") : ZoneId.of("Pacific/Kiritimati");
        DateTimeProvider provider = providerWith(Clock.fixed(NANO_INSTANT, clockZone));

        TemporalAccessor now = provider.getNow().orElseThrow();

        assertThat(now).isEqualTo(LocalDateTime.ofInstant(Instant.parse("2026-11-13T05:58:44.740903Z"), ZoneId.systemDefault()));
    }

    @Test
    @DisplayName("Clock 빈이 없어도 동작하고 마이크로초 아래가 0이다")
    void provider_noClockBean_usesSystemMicros() {
        DateTimeProvider provider = providerWith(null);

        LocalDateTime now = (LocalDateTime) provider.getNow().orElseThrow();

        assertThat(now.getNano() % 1_000).isZero();
    }

    @Test
    @DisplayName("Clock 빈을 새로 등록하지 않는다 — 테스트가 자기 clock 빈을 두어도 충돌하지 않는다")
    void config_doesNotRegisterClockBean() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.setAllowBeanDefinitionOverriding(false);
            context.register(JpaAuditingProviderOnly.class, TestClockConfig.class);
            context.refresh();

            assertThat(context.getBeansOfType(Clock.class)).containsOnlyKeys("clock");
            assertThat(context.getBean(DateTimeProvider.class).getNow().orElseThrow())
                    .isEqualTo(LocalDateTime.ofInstant(Instant.parse("2026-11-13T05:58:44.740903Z"), ZoneId.systemDefault()));
        }
    }

    private static DateTimeProvider providerWith(Clock clock) {
        return new JpaAuditingConfig().routinelyAuditingDateTimeProvider(fixedProvider(clock));
    }

    private static ObjectProvider<Clock> fixedProvider(Clock clock) {
        return new ObjectProvider<>() {
            @Override
            public Clock getObject() {
                return clock;
            }

            @Override
            public Clock getIfAvailable() {
                return clock;
            }
        };
    }

    /** {@code @EnableJpaAuditing}은 JPA 메타모델이 필요해 여기선 시각 공급자 빈 등록만 본다. */
    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    static class JpaAuditingProviderOnly {
        @org.springframework.context.annotation.Bean(JpaAuditingConfig.DATE_TIME_PROVIDER)
        DateTimeProvider provider(ObjectProvider<Clock> clock) {
            return new JpaAuditingConfig().routinelyAuditingDateTimeProvider(clock);
        }
    }

    /** 실제 테스트들(ExecutionFeedIntegrationTest 등)처럼 이름이 clock인 빈을 둔다. */
    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    static class TestClockConfig {
        @org.springframework.context.annotation.Bean
        Clock clock() {
            return Clock.fixed(NANO_INSTANT, ZoneId.of("Asia/Seoul"));
        }
    }
}
