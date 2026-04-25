package com.routinely.user_service.application.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.routinely.core.exception.BusinessException;
import com.routinely.user_service.application.auth.dto.LoginCommand;
import com.routinely.user_service.application.auth.dto.LoginResult;
import com.routinely.user_service.domain.User;
import com.routinely.user_service.domain.UserRepository;
import com.routinely.user_service.domain.UserRole;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest
@ActiveProfiles("test")
@DisplayName("Auth Token Integration")
class AuthTokenIntegrationTest {

    @SuppressWarnings("resource")
    static final GenericContainer<?> redisContainer = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @Autowired
    private AuthService authService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @BeforeAll
    static void startRedisContainer() {
        if (!redisContainer.isRunning()) {
            redisContainer.start();
        }
    }

    @AfterAll
    static void stopRedisContainer() {
        if (redisContainer.isRunning()) {
            redisContainer.stop();
        }
    }

    @DynamicPropertySource
    static void registerRedisProperties(DynamicPropertyRegistry registry) {
        if (!redisContainer.isRunning()) {
            redisContainer.start();
        }
        registry.add("spring.data.redis.host", redisContainer::getHost);
        registry.add("spring.data.redis.port", redisContainer::getFirstMappedPort);
    }

    @BeforeEach
    void setUp() {
        userRepository.deleteAll();
        flushRedis();
    }

    @AfterEach
    void tearDown() {
        flushRedis();
    }

    @Test
    @DisplayName("로그인후_토큰갱신하면_기존_refreshToken은_재사용할수없다")
    void refresh_afterLogin_invalidatesPreviousRefreshToken() {
        User savedUser = saveUser("user@routinely.com", "password123!");

        LoginResult loginResult = authService.login(new LoginCommand(savedUser.getEmail(), "password123!"));
        LoginResult refreshed = authService.refresh(loginResult.refreshToken());

        assertThat(loginResult.accessToken()).isNotBlank();
        assertThat(loginResult.refreshToken()).isNotBlank();
        assertThat(refreshed.accessToken()).isNotBlank();
        assertThat(refreshed.refreshToken()).isNotBlank();
        assertThat(refreshed.refreshToken()).isNotEqualTo(loginResult.refreshToken());

        Throwable replayAttempt = catchThrowable(() -> authService.refresh(loginResult.refreshToken()));
        assertThat(replayAttempt)
                .isInstanceOf(BusinessException.class)
                .hasMessage("인증이 필요합니다.");

        LoginResult secondRefresh = authService.refresh(refreshed.refreshToken());
        assertThat(secondRefresh.accessToken()).isNotBlank();
        assertThat(secondRefresh.refreshToken()).isNotBlank();
    }

    @Test
    @DisplayName("로그아웃하면_refreshToken이_삭제되어_재사용할수없다")
    void logout_invalidatesRefreshToken() {
        User savedUser = saveUser("logout-user@routinely.com", "password123!");
        LoginResult loginResult = authService.login(new LoginCommand(savedUser.getEmail(), "password123!"));

        authService.logout(loginResult.refreshToken());

        Throwable refreshAfterLogout = catchThrowable(() -> authService.refresh(loginResult.refreshToken()));
        assertThat(refreshAfterLogout)
                .isInstanceOf(BusinessException.class)
                .hasMessage("인증이 필요합니다.");
    }

    @Test
    @DisplayName("동시에_같은_refreshToken으로_갱신하면_한요청만_성공한다")
    void refresh_concurrentRequests_onlyOneSucceeds() throws Exception {
        User savedUser = saveUser("concurrent-user@routinely.com", "password123!");
        LoginResult loginResult = authService.login(new LoginCommand(savedUser.getEmail(), "password123!"));

        ExecutorService executorService = Executors.newFixedThreadPool(2);
        CountDownLatch startLatch = new CountDownLatch(1);
        try {
            Callable<Object> refreshTask = () -> {
                startLatch.await(5, TimeUnit.SECONDS);
                try {
                    return authService.refresh(loginResult.refreshToken());
                } catch (BusinessException e) {
                    return e;
                }
            };

            List<Future<Object>> futures = List.of(
                    executorService.submit(refreshTask),
                    executorService.submit(refreshTask)
            );

            startLatch.countDown();

            List<Object> results = new ArrayList<>();
            for (Future<Object> future : futures) {
                results.add(getFutureValue(future));
            }

            long successCount = results.stream().filter(LoginResult.class::isInstance).count();
            long failureCount = results.stream().filter(BusinessException.class::isInstance).count();

            assertThat(successCount).isEqualTo(1);
            assertThat(failureCount).isEqualTo(1);

            BusinessException failure = results.stream()
                    .filter(BusinessException.class::isInstance)
                    .map(BusinessException.class::cast)
                    .findFirst()
                    .orElseThrow();
            assertThat(failure.getMessage()).isEqualTo("인증이 필요합니다.");

            LoginResult success = results.stream()
                    .filter(LoginResult.class::isInstance)
                    .map(LoginResult.class::cast)
                    .findFirst()
                    .orElseThrow();
            assertThat(success.refreshToken()).isNotBlank();
        } finally {
            executorService.shutdownNow();
        }
    }

    private User saveUser(String email, String rawPassword) {
        return userRepository.save(User.builder()
                .email(email)
                .passwordHash(passwordEncoder.encode(rawPassword))
                .nickname(email.substring(0, email.indexOf('@')))
                .role(UserRole.USER)
                .isActive(true)
                .build());
    }

    private void flushRedis() {
        redisTemplate.execute((RedisCallback<Void>) connection -> {
            connection.serverCommands().flushDb();
            return null;
        });
    }

    private Object getFutureValue(Future<Object> future)
            throws InterruptedException, ExecutionException, TimeoutException {
        return future.get(10, TimeUnit.SECONDS);
    }
}
