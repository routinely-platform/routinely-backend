package com.routinely.jpa.entity;

import com.routinely.jpa.config.JpaAuditingConfig;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase
@Import(JpaAuditingConfig.class)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"
})
@DisplayName("BaseEntity")
// 테스트 시계가 컨텍스트 빈이라 메서드끼리 공유한다 — 병렬 실행을 켜도 이 클래스는 순차로 돈다
@Execution(ExecutionMode.SAME_THREAD)
class BaseEntityTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = BaseEntityTest.class)
    @EnableJpaRepositories(basePackageClasses = BaseEntityTest.class, considerNestedRepositories = true)
    static class TestConfig {

        /** 나노초가 있는 시계를 일부러 그대로 둔다 — 마이크로초로 자르는 건 Auditing 경로의 책임이다(#213). */
        @Bean
        TestClock clock() {
            return new TestClock(NANO_INSTANT);
        }
    }

    /** 리눅스 시계처럼 나노초 9자리를 주는 조정 가능한 시계. macOS 로컬 시계 정밀도에 기대지 않는다. */
    static class TestClock extends Clock {
        private Instant now;

        TestClock(Instant now) {
            this.now = now;
        }

        void set(Instant instant) {
            now = instant;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override public ZoneId getZone() { return ZoneId.of("Asia/Seoul"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    @Autowired
    TestEntityRepository repository;

    @Autowired
    EntityManager entityManager;

    @Autowired
    TestClock clock;

    private static final Instant NANO_INSTANT = Instant.parse("2026-11-13T05:58:44.740903532Z");

    /** 컨텍스트가 테스트 사이에 재사용되므로 시계를 매번 되돌린다. */
    @BeforeEach
    void resetClock() {
        clock.set(NANO_INSTANT);
    }

    private static LocalDateTime microsOf(String instant) {
        return LocalDateTime.ofInstant(Instant.parse(instant), ZoneId.systemDefault());
    }

    @Test
    @DisplayName("저장시_createdAt과updatedAt자동주입 — Clock의 나노초를 마이크로초로 버린 값")
    void persist_setsCreatedAndUpdatedAt() {
        TestEntity entity = new TestEntity();
        entity.setName("initial");

        TestEntity saved = repository.saveAndFlush(entity);

        assertThat(saved.getCreatedAt()).isEqualTo(microsOf("2026-11-13T05:58:44.740903Z"));
        assertThat(saved.getUpdatedAt()).isEqualTo(saved.getCreatedAt());
    }

    @Test
    @DisplayName("저장직후_메모리값과_DB재조회값이_같다 — 나노초 시계에서도 (#213)")
    void persist_inMemoryValueEqualsReloadedValue() {
        TestEntity entity = new TestEntity();
        entity.setName("roundtrip");
        TestEntity saved = repository.saveAndFlush(entity);
        entityManager.clear();

        TestEntity reloaded = repository.findById(saved.getId()).orElseThrow();

        assertThat(reloaded.getCreatedAt()).isEqualTo(saved.getCreatedAt());
        assertThat(reloaded.getUpdatedAt()).isEqualTo(saved.getUpdatedAt());
    }

    @Test
    @DisplayName("반올림경계_…999999500도_다음초로_넘어가지않는다")
    void persist_roundingBoundary_staysInSameSecond() {
        clock.set(Instant.parse("2026-11-13T05:58:59.999999500Z"));
        TestEntity entity = new TestEntity();
        entity.setName("boundary");
        TestEntity saved = repository.saveAndFlush(entity);
        entityManager.clear();

        TestEntity reloaded = repository.findById(saved.getId()).orElseThrow();

        assertThat(saved.getCreatedAt()).isEqualTo(microsOf("2026-11-13T05:58:59.999999Z"));
        assertThat(reloaded.getCreatedAt()).isEqualTo(saved.getCreatedAt());
    }

    @Test
    @DisplayName("수정시_updatedAt만갱신_createdAt유지")
    void update_refreshesUpdatedAtOnly() {
        TestEntity entity = new TestEntity();
        entity.setName("before");
        TestEntity saved = repository.saveAndFlush(entity);
        LocalDateTime originalCreated = saved.getCreatedAt();

        clock.advance(Duration.ofMillis(10));
        saved.setName("after");
        TestEntity updated = repository.saveAndFlush(saved);

        assertThat(updated.getCreatedAt()).isEqualTo(originalCreated);
        assertThat(updated.getUpdatedAt()).isAfter(originalCreated);
    }

    @Test
    @DisplayName("createdAt_업데이트불가_불변")
    void createdAt_isImmutable() {
        TestEntity entity = new TestEntity();
        entity.setName("frozen");
        TestEntity saved = repository.saveAndFlush(entity);
        Long id = saved.getId();
        // 기준값은 저장 직후 메모리 값이다 — Auditing이 마이크로초로 만들어 DB 값과 같다(#213).
        // #200에서는 이 값이 나노초라 DB 값끼리 비교하도록 바꿔 두었다.
        LocalDateTime originalCreated = saved.getCreatedAt();
        entityManager.clear();

        TestEntity persisted = repository.findById(id).orElseThrow();

        ReflectionTestUtils.setField(persisted, "createdAt", originalCreated.minusYears(10));
        persisted.setName("mutated");
        repository.saveAndFlush(persisted);
        entityManager.clear();

        TestEntity reloaded = repository.findById(id).orElseThrow();
        assertThat(reloaded.getCreatedAt()).isEqualTo(originalCreated);
    }

    @Entity(name = "BaseEntityTest$TestEntity")
    @Getter
    @Setter
    @NoArgsConstructor(access = AccessLevel.PUBLIC)
    static class TestEntity extends BaseEntity {

        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;

        private String name;
    }

    interface TestEntityRepository extends JpaRepository<TestEntity, Long> {
    }
}
