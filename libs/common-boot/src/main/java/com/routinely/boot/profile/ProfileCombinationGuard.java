package com.routinely.boot.profile;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * 환경 프로파일({@code local} · {@code prod})이 둘 이상 함께 적용되면 기동을 멈춘다(#215).
 *
 * <p>프로파일 yaml은 나중에 적힌 쪽이 같은 키를 덮어쓰고, 한쪽에만 있는 키는 순서와 상관없이 남는다.
 * 그래서 {@code local,prod}든 {@code prod,local}이든 local 전용 값(문서 라우트 · Swagger · S3 localhost ·
 * 비밀 기본값 등)이 prod에 섞인다. 값 하나하나를 막는 대신 조합 자체를 기동 단계에서 거부한다.
 *
 * <p>순서를 지정하지 않으므로 {@code ConfigDataEnvironmentPostProcessor}(yaml · env · 커맨드라인의
 * {@code spring.profiles.active} 해석) 뒤에 실행된다. 컨텍스트와 빈을 만들기 전이라 섞인 설정으로
 * DB · Kafka · Eureka · gRPC에 연결하지 않는다. {@code observability} · {@code test} 같은 애드온은 보지 않는다.
 */
public class ProfileCombinationGuard implements EnvironmentPostProcessor {

    /** 하나만 고를 수 있는 환경 프로파일. 환경이 늘면 여기에 더한다. */
    static final Set<String> ENVIRONMENT_PROFILES = Set.of("local", "prod");

    static final String MESSAGE_PREFIX = "local과 prod 프로파일은 함께 적용할 수 없다.";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        List<String> applied = appliedProfiles(environment);
        Set<String> environments = new LinkedHashSet<>(applied);
        environments.retainAll(ENVIRONMENT_PROFILES);
        if (environments.size() > 1) {
            // 적용 프로파일 전체가 아니라 충돌한 환경 프로파일(고정 상수)만 남긴다. 프로파일 이름은
            // ${...}로 다른 설정값을 참조해 만들 수 있어, 전체를 찍으면 그 값(비밀 포함)이 로그에 나간다.
            throw new IllegalStateException(MESSAGE_PREFIX + " 충돌=" + environments
                    + ". 환경 프로파일(local · prod)은 하나만 켜고 observability · test는 애드온으로 조합한다.");
        }
    }

    /**
     * 실제로 적용되는 프로파일. Spring과 같은 규칙으로, 활성 프로파일이 없으면 기본 프로파일
     * ({@code spring.profiles.default})이 적용된다.
     */
    private static List<String> appliedProfiles(ConfigurableEnvironment environment) {
        String[] active = environment.getActiveProfiles();
        return Arrays.asList(active.length > 0 ? active : environment.getDefaultProfiles());
    }
}
