package com.routinely.boot.profile;

import java.util.HashMap;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.Ordered;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

/**
 * 서비스 메인 클래스로 기동 1단계(환경 준비)까지만 실행해 프로파일 가드의 판정을 돌려준다(#215).
 *
 * <p>환경 준비 이벤트의 가장 늦은 순서에 {@link EnvironmentReady}를 던지는 리스너를 붙인다.
 * 가드는 그보다 앞({@code EnvironmentPostProcessorApplicationListener})에서 돈다. 그래서
 * <ul>
 *   <li>가드가 막으면 가드의 {@link IllegalStateException}이 나온다</li>
 *   <li>가드가 허용했거나 <b>가드가 등록되지 않았으면</b> {@link EnvironmentReady}가 나온다</li>
 * </ul>
 * 어느 쪽이든 컨텍스트를 만들기 전에 끝나므로 DB · Kafka · Eureka 없이 돈다.
 *
 * <p>이 모듈은 common-boot에 의존하지 않는다. 가드는 서비스 자신의 {@code implementation} 의존으로만
 * 클래스패스에 들어와야 하므로, 가드 판정은 클래스 참조 대신 메시지 접두사({@link #GUARD_MESSAGE_PREFIX})로 한다.
 * 접두사가 가드와 같은지는 common-boot의 {@code ProfileCombinationGuardTest}가 확인한다.
 *
 * <p>{@code web-application-type=none}만으로는 DB · gRPC 초기화를 막지 못하고, gateway는 yaml의
 * {@code reactive}가 그 값을 덮어쓰기 때문에 이 방식을 쓴다.
 *
 * <p><b>실행 환경과 격리한다.</b> 시스템 환경변수는 항상 빈 맵(또는 {@link #runWithEnv}로 준 맵)으로 바꾸고,
 * 시스템 속성에서 {@code spring.profiles.*} · {@code spring.config.*} · {@code spring.application.json}을 뺀다. 테스트를 돌리는 셸에
 * {@code SPRING_PROFILES_ACTIVE}가 남아 있어도 판정이 흔들리지 않게 하기 위해서다(Codex 리뷰 R2).
 */
public final class ProfileGuardProbe {

    /** {@code ProfileCombinationGuard.MESSAGE_PREFIX}와 같아야 한다. */
    public static final String GUARD_MESSAGE_PREFIX = "local과 prod 프로파일은 함께 적용할 수 없다.";

    private ProfileGuardProbe() {
    }

    /** 커맨드라인 인자로 실행한다. 예: {@code run(App.class, "--spring.profiles.active=local,prod")} */
    public static RuntimeException run(Class<?> source, String... args) {
        return run(source, Map.of(), args);
    }

    /** 시스템 환경변수(예: {@code SPRING_PROFILES_ACTIVE})를 대신 넣어 실행한다. */
    public static RuntimeException runWithEnv(Class<?> source, Map<String, Object> env, String... args) {
        return run(source, env, args);
    }

    private static RuntimeException run(Class<?> source, Map<String, Object> env, String[] args) {
        SpringApplication application = new SpringApplication(source);
        application.setLogStartupInfo(false);
        application.setRegisterShutdownHook(false);
        application.addListeners(new StopAfterEnvironmentPrepared());
        application.setEnvironment(isolatedEnvironment(env));
        try {
            application.run(args);
        } catch (RuntimeException e) {
            return e;
        }
        throw new AssertionError("환경 준비 단계에서 멈추지 않고 기동이 끝났다");
    }

    private static StandardEnvironment isolatedEnvironment(Map<String, Object> env) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().replace(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, env));

        Map<String, Object> systemProperties = new HashMap<>();
        System.getProperties().forEach((key, value) -> {
            String name = String.valueOf(key);
            if (!isProfileInput(name)) {
                systemProperties.put(name, value);
            }
        });
        environment.getPropertySources().replace(
                StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME,
                new MapPropertySource(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME, systemProperties));
        return environment;
    }

    /**
     * 프로파일 판정에 끼어들 수 있는 시스템 속성. {@code spring.application.json}은 Boot가 ConfigData 전에
     * 별도 프로퍼티 소스로 펼치므로, 그 안의 {@code spring.profiles.active}가 테스트 조건을 덮는다(Codex 재리뷰 R2b).
     */
    private static boolean isProfileInput(String name) {
        return name.startsWith("spring.profiles.")
                || name.startsWith("spring.config.")
                || name.equals("spring.application.json");
    }

    /** 가드가 차단했는지. 가드가 등록되지 않았으면 false다. */
    public static boolean blockedByGuard(RuntimeException result) {
        return result instanceof IllegalStateException
                && result.getMessage() != null
                && result.getMessage().startsWith(GUARD_MESSAGE_PREFIX);
    }

    /** 환경 준비가 끝날 때까지 가드가 막지 않았다 — 허용했거나, 등록되지 않았다. */
    public static final class EnvironmentReady extends RuntimeException {

        EnvironmentReady() {
            super("환경 준비가 끝날 때까지 프로파일 가드가 막지 않았다", null, false, false);
        }
    }

    private static final class StopAfterEnvironmentPrepared
            implements ApplicationListener<ApplicationEnvironmentPreparedEvent>, Ordered {

        @Override
        public void onApplicationEvent(ApplicationEnvironmentPreparedEvent event) {
            throw new EnvironmentReady();
        }

        @Override
        public int getOrder() {
            return Ordered.LOWEST_PRECEDENCE;
        }
    }
}
