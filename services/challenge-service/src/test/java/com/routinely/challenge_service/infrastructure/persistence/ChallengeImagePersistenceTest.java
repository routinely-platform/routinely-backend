package com.routinely.challenge_service.infrastructure.persistence;

import com.routinely.challenge_service.domain.challenge.Challenge;
import com.routinely.challenge_service.domain.challenge.ChallengeImage;
import com.routinely.challenge_service.domain.challenge.ChallengeRepository;
import com.routinely.jpa.config.JpaAuditingConfig;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase
@Import(JpaAuditingConfig.class)
@TestPropertySource(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"
})
@DisplayName("Challenge 대표 이미지 매핑")
class ChallengeImagePersistenceTest {

    @Autowired
    EntityManager entityManager;

    @Autowired
    ChallengeRepository challengeRepository;

    @Test
    @DisplayName("대표이미지를_저장하면_image_url과_image_object_key로_매핑되어_왕복된다")
    void persistsAndReadsChallengeImage() {
        Challenge challenge = challenge();
        challenge.changeImage(ChallengeImage.of(
                "https://cdn.routinely.com/challenge-images/2026/07/x.jpg",
                "challenge-images/2026/07/x.jpg"));
        Long id = challengeRepository.save(challenge).getId();
        entityManager.flush();
        entityManager.clear();

        Challenge found = challengeRepository.findById(id).orElseThrow();
        assertThat(found.getImageUrl()).isEqualTo("https://cdn.routinely.com/challenge-images/2026/07/x.jpg");
        assertThat(found.getImageObjectKey()).isEqualTo("challenge-images/2026/07/x.jpg");
    }

    @Test
    @DisplayName("이미지없이_저장하면_imageUrl과_objectKey가_null이다")
    void persistsChallengeWithoutImage() {
        Long id = challengeRepository.save(challenge()).getId();
        entityManager.flush();
        entityManager.clear();

        Challenge found = challengeRepository.findById(id).orElseThrow();
        assertThat(found.getImageUrl()).isNull();
        assertThat(found.getImageObjectKey()).isNull();
    }

    @Test
    @DisplayName("이미지제거후_저장하면_컬럼이_null로_갱신된다")
    void removesChallengeImage() {
        Challenge challenge = challenge();
        challenge.changeImage(ChallengeImage.of("url", "challenge-images/2026/07/x.jpg"));
        Long id = challengeRepository.save(challenge).getId();
        entityManager.flush();
        entityManager.clear();

        Challenge stored = challengeRepository.findById(id).orElseThrow();
        stored.removeImage();
        challengeRepository.save(stored);
        entityManager.flush();
        entityManager.clear();

        Challenge found = challengeRepository.findById(id).orElseThrow();
        assertThat(found.getImageUrl()).isNull();
        assertThat(found.getImageObjectKey()).isNull();
    }

    private Challenge challenge() {
        return Challenge.builder()
                .creatorUserId(1L)
                .title("매일 30분 독서")
                .maxMembers(10)
                .categoryCode("READING")
                .startedAt(LocalDate.of(2026, 7, 20))
                .endedAt(LocalDate.of(2026, 8, 20))
                .build();
    }
}
