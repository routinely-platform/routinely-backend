package com.routinely.core.time;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.AccessTarget;
import com.tngtech.archunit.core.domain.JavaAccess;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.List;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * "서버 시각은 주입된 Clock에서만, Clock은 {@link RoutinelyClocks}에서만 만든다"를 빌드에서 확인한다(#213).
 *
 * <p>DB 컬럼은 마이크로초까지만 담는데 JVM 시계(Linux)는 나노초를 준다. 시계를 직접 읽거나 팩토리를 건너뛴 Clock을
 * 만들면 저장 직후 메모리 값과 DB 재조회 값이 다시 어긋난다. macOS 시계는 마이크로초라 로컬 테스트로는 안 보인다.
 *
 * <ul>
 *   <li>① 시계를 직접 읽는 {@code now()} · {@code now(ZoneId)} 금지 — {@code LocalDateTime} · {@code LocalTime} ·
 *       {@code Instant} · {@code OffsetDateTime} · {@code ZonedDateTime}. {@code now(Clock)}만 쓴다.
 *       {@code LocalDate}는 날짜라 정밀도 문제가 없어 대상 밖이다.</li>
 *   <li>② {@code Clock}의 정적 팩토리({@code system*} · {@code tick*} · {@code offset} · {@code fixed})는
 *       {@link RoutinelyClocks} 안에서만 — {@code now(Clock.systemUTC())}처럼 ①을 피해 가는 경로와
 *       팩토리를 건너뛴 {@code Clock.system(zone)} 빈을 막는다.</li>
 * </ul>
 * 호출과 메서드 참조({@code Clock::systemUTC}) 모두 잡는다. {@code java.util.Date}는 대상 밖이다
 * (JWT 발급 — 밀리초 · DB 미저장).
 */
public final class ServerTimeRules {

    private static final String FACTORY_PACKAGE = "com.routinely.core.time";

    public static final ArchRule NO_DIRECT_SYSTEM_TIME = noClasses()
            .should().accessTargetWhere(anyOf(
                    nowOf(LocalDateTime.class), nowOf(LocalTime.class), nowOf(OffsetDateTime.class),
                    nowOf(ZonedDateTime.class), target(Instant.class, "now")))
            .because("서버 시각은 주입된 Clock에서만 만든다 — LocalDateTime.now(clock) (#213)")
            .allowEmptyShould(true);

    public static final ArchRule CLOCKS_ONLY_FROM_FACTORY = noClasses()
            .that().resideOutsideOfPackage(FACTORY_PACKAGE)
            .should().accessTargetWhere(anyOf(
                    target(Clock.class, "system", ZoneId.class),
                    target(Clock.class, "systemUTC"),
                    target(Clock.class, "systemDefaultZone"),
                    target(Clock.class, "tick", Clock.class, Duration.class),
                    target(Clock.class, "tickMillis", ZoneId.class),
                    target(Clock.class, "tickSeconds", ZoneId.class),
                    target(Clock.class, "tickMinutes", ZoneId.class),
                    target(Clock.class, "offset", Clock.class, Duration.class),
                    target(Clock.class, "fixed", Instant.class, ZoneId.class)))
            .because("Clock은 RoutinelyClocks에서만 만든다 — 마이크로초 절단을 건너뛰지 않게 (#213)")
            .allowEmptyShould(true);

    private ServerTimeRules() {
    }

    /**
     * {@code basePackage} 아래 운영 코드(테스트 제외)에 두 규칙을 건다. 서비스 테스트에서 부른다.
     * 서비스가 의존하는 {@code libs/*} jar도 같은 패키지라 함께 검사된다.
     */
    public static void check(String basePackage) {
        JavaClasses classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(basePackage);
        NO_DIRECT_SYSTEM_TIME.check(classes);
        CLOCKS_ONLY_FROM_FACTORY.check(classes);
    }

    private static DescribedPredicate<JavaAccess<?>> nowOf(Class<?> type) {
        return anyOf(target(type, "now"), target(type, "now", ZoneId.class));
    }

    @SafeVarargs
    private static DescribedPredicate<JavaAccess<?>> anyOf(DescribedPredicate<JavaAccess<?>>... predicates) {
        List<DescribedPredicate<JavaAccess<?>>> list = Arrays.asList(predicates);
        return DescribedPredicate.describe(
                list.stream().map(DescribedPredicate::getDescription).reduce((a, b) -> a + " or " + b).orElse(""),
                access -> list.stream().anyMatch(p -> p.test(access)));
    }

    private static DescribedPredicate<JavaAccess<?>> target(Class<?> owner, String name, Class<?>... parameterTypes) {
        List<String> expected = Arrays.stream(parameterTypes).map(Class::getName).toList();
        String description = owner.getSimpleName() + "." + name + "(" + String.join(", ",
                Arrays.stream(parameterTypes).map(Class::getSimpleName).toList()) + ")";
        return DescribedPredicate.describe(description, access ->
                access.getTarget() instanceof AccessTarget.CodeUnitAccessTarget target
                        && target.getOwner().isEquivalentTo(owner)
                        && target.getName().equals(name)
                        && target.getRawParameterTypes().stream().map(JavaClass::getName).toList().equals(expected));
    }
}
