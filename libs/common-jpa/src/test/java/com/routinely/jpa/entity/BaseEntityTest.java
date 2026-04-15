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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase
@Import(JpaAuditingConfig.class)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"
})
@DisplayName("BaseEntity")
class BaseEntityTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = BaseEntityTest.class)
    @EnableJpaRepositories(basePackageClasses = BaseEntityTest.class, considerNestedRepositories = true)
    static class TestConfig {
    }

    @Autowired
    TestEntityRepository repository;

    @Autowired
    EntityManager entityManager;

    @Test
    @DisplayName("저장시_createdAt과updatedAt자동주입")
    void persist_setsCreatedAndUpdatedAt() {
        TestEntity entity = new TestEntity();
        entity.setName("initial");

        TestEntity saved = repository.saveAndFlush(entity);

        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();
        assertThat(saved.getCreatedAt()).isEqualTo(saved.getUpdatedAt());
    }

    @Test
    @DisplayName("수정시_updatedAt만갱신_createdAt유지")
    void update_refreshesUpdatedAtOnly() throws InterruptedException {
        TestEntity entity = new TestEntity();
        entity.setName("before");
        TestEntity saved = repository.saveAndFlush(entity);
        LocalDateTime originalCreated = saved.getCreatedAt();

        Thread.sleep(10);
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
        LocalDateTime originalCreated = saved.getCreatedAt();

        ReflectionTestUtils.setField(saved, "createdAt", originalCreated.minusYears(10));
        saved.setName("mutated");
        repository.saveAndFlush(saved);
        entityManager.clear();

        TestEntity reloaded = repository.findById(saved.getId()).orElseThrow();
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
