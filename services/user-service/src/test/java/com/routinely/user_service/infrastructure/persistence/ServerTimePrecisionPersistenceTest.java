package com.routinely.user_service.infrastructure.persistence;

import com.routinely.core.time.RoutinelyClocks;
import com.routinely.jpa.config.JpaAuditingConfig;
import com.routinely.user_service.application.user.UserService;
import com.routinely.user_service.application.user.dto.ProfileResult;
import com.routinely.user_service.application.user.dto.UpdateProfileCommand;
import com.routinely.user_service.domain.User;
import com.routinely.user_service.domain.UserRepository;
import com.routinely.user_service.infrastructure.config.UserProfileProperties;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 프로필 응답의 시각이 저장 직후와 재조회 뒤에 같은지 본다(#213).
 *
 * <p>닉네임 변경 응답의 {@code nicknameChangeableAt}은 flush 직후 <b>메모리</b> {@code nicknameUpdatedAt}에
 * 쿨다운을 더해 만든다. 나노초 원천을 운영과 같은 팩토리 {@link RoutinelyClocks#micros(Clock)}에 넣어
 * PATCH 응답과 이후 GET 응답이 같은지, 기대값 {@code .740903}과 같은지 확인한다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaAuditingConfig.class, UserService.class, ServerTimePrecisionPersistenceTest.Config.class})
abstract class ServerTimePrecisionPersistenceTest {

    static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    static final Instant NANO_INSTANT = Instant.parse("2026-11-13T05:58:44.740903532Z");
    static final long COOLDOWN_DAYS = 30;
    static final LocalDateTime AUDITING_MICROS =
            LocalDateTime.ofInstant(Instant.parse("2026-11-13T05:58:44.740903Z"), ZoneId.systemDefault());

    @TestConfiguration
    static class Config {
        @Bean
        Clock clock() {
            return RoutinelyClocks.micros(Clock.fixed(NANO_INSTANT, SEOUL));
        }

        @Bean
        UserProfileProperties userProfileProperties() {
            return new UserProfileProperties(COOLDOWN_DAYS);
        }
    }

    @Autowired UserService userService;
    @Autowired UserRepository users;
    @Autowired EntityManager entityManager;

    /**
     * 운영처럼 PATCH가 자기 트랜잭션에서 커밋하고, GET이 새 트랜잭션에서 커밋된 행을 읽는다.
     * 테스트 트랜잭션에 묶이면 같은 영속성 컨텍스트 · 같은 트랜잭션 안의 재조회가 되어 커밋 경계를 건너뛴다.
     */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("닉네임 변경 PATCH 응답과 이후 GET 응답(커밋 뒤 새 트랜잭션)의 nicknameChangeableAt · createdAt이 같다")
    void updateProfile_responseEqualsReloadedProfile() {
        Long userId = users.saveAndFlush(User.createUser("precision@routinely.test", "hash", "before")).getId();
        try {
            ProfileResult patched = userService.updateProfile(new UpdateProfileCommand(userId, "after", "bio"));
            ProfileResult reloaded = userService.getMyProfile(userId);

            assertThat(patched.nicknameChangeableAt())
                    .isEqualTo(LocalDateTime.parse("2026-11-13T14:58:44.740903").plusDays(COOLDOWN_DAYS));
            assertThat(reloaded.nicknameChangeableAt()).isEqualTo(patched.nicknameChangeableAt());
            assertThat(reloaded.createdAt()).isEqualTo(patched.createdAt());
        } finally {
            // 테스트 트랜잭션 밖이라 롤백되지 않는다 — 직접 지운다
            users.deleteById(userId);
        }
    }

    @Test
    @DisplayName("Auditing — 가입 직후 createdAt이 DB 재조회 값과 같다")
    void userAuditing_inMemoryEqualsReloaded() {
        User saved = users.saveAndFlush(User.createUser("audit@routinely.test", "hash", "audit"));
        entityManager.clear();

        User reloaded = users.findById(saved.getId()).orElseThrow();

        assertThat(saved.getCreatedAt()).isEqualTo(AUDITING_MICROS);
        assertThat(reloaded.getCreatedAt()).isEqualTo(saved.getCreatedAt());
    }
}
