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
        Long id = repository.saveAndFlush(entity).getId();
        entityManager.clear();

        // 기준값도 DB에서 읽는다. 감사(auditing) 값은 메모리에 JVM 시각 정밀도 그대로 남고(리눅스 CI에서 나노초 9자리 확인),
        // DB에는 컬럼 정밀도(마이크로초)로 반올림돼 저장된다. 메모리 값과 비교하면 실행 환경의 시계 정밀도에 따라 결과가 갈린다.
        TestEntity persisted = repository.findById(id).orElseThrow();
        LocalDateTime originalCreated = persisted.getCreatedAt();

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
