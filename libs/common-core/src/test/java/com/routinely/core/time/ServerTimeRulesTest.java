package com.routinely.core.time;

import com.routinely.core.time.violation.DirectNowViolations;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.Test;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

@DisplayName("ServerTimeRules — 서버 시각 정적 검사")
class ServerTimeRulesTest {

    static Stream<Arguments> violations() {
        return Stream.of(
                arguments(DirectNowViolations.NoArgNow.class, ServerTimeRules.NO_DIRECT_SYSTEM_TIME),
                arguments(DirectNowViolations.ZoneNow.class, ServerTimeRules.NO_DIRECT_SYSTEM_TIME),
                arguments(DirectNowViolations.InstantNow.class, ServerTimeRules.NO_DIRECT_SYSTEM_TIME),
                arguments(DirectNowViolations.SystemUtcClock.class, ServerTimeRules.CLOCKS_ONLY_FROM_FACTORY),
                arguments(DirectNowViolations.SystemClockBean.class, ServerTimeRules.CLOCKS_ONLY_FROM_FACTORY),
                arguments(DirectNowViolations.MethodReference.class, ServerTimeRules.CLOCKS_ONLY_FROM_FACTORY));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("violations")
    @DisplayName("시계를 직접 읽거나 팩토리 밖에서 Clock을 만들면 잡는다")
    void rule_detectsViolation(Class<?> violation, ArchRule rule) {
        JavaClasses classes = new ClassFileImporter().importClasses(violation);

        assertThat(rule.evaluate(classes).hasViolation()).isTrue();
    }

    @Test
    @DisplayName("now(Clock)과 LocalDate.now()는 허용한다")
    void rules_allowInjectedClockAndDates() {
        JavaClasses classes = new ClassFileImporter().importClasses(DirectNowViolations.Allowed.class);

        assertThat(ServerTimeRules.NO_DIRECT_SYSTEM_TIME.evaluate(classes).hasViolation()).isFalse();
        assertThat(ServerTimeRules.CLOCKS_ONLY_FROM_FACTORY.evaluate(classes).hasViolation()).isFalse();
    }

    @Test
    @DisplayName("팩토리(RoutinelyClocks)는 Clock 정적 팩토리를 써도 된다")
    void factory_isAllowedToCreateClocks() {
        JavaClasses classes = new ClassFileImporter().importClasses(RoutinelyClocks.class);

        assertThat(ServerTimeRules.CLOCKS_ONLY_FROM_FACTORY.evaluate(classes).hasViolation()).isFalse();
    }
}
