package com.routinely.challenge_service.presentation.rest.challenge.dto.request;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("UpdateChallengeRequest")
class UpdateChallengeRequestTest {

    private static ValidatorFactory validatorFactory;
    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    @AfterAll
    static void closeValidatorFactory() {
        validatorFactory.close();
    }

    @Test
    @DisplayName("수정필드가하나이상있고유효하면_검증에성공한다")
    void validate_whenAnyFieldProvidedAndValid_succeeds() {
        UpdateChallengeRequest request = new UpdateChallengeRequest(
                "새 제목",
                "새 설명",
                true,
                10,
                LocalDate.now().plusDays(1),
                LocalDate.now().plusDays(30),
                null
        );

        Set<ConstraintViolation<UpdateChallengeRequest>> violations = validator.validate(request);

        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("시작일만수정하면_요청레벨날짜범위검증은통과한다")
    void validate_whenOnlyStartedAtProvided_succeeds() {
        UpdateChallengeRequest request = new UpdateChallengeRequest(
                null,
                null,
                null,
                null,
                LocalDate.now().plusDays(10),
                null,
                null
        );

        Set<ConstraintViolation<UpdateChallengeRequest>> violations = validator.validate(request);

        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("종료일만수정하면_요청레벨날짜범위검증은통과한다")
    void validate_whenOnlyEndedAtProvided_succeeds() {
        UpdateChallengeRequest request = new UpdateChallengeRequest(
                null,
                null,
                null,
                null,
                null,
                LocalDate.now().plusDays(10),
                null
        );

        Set<ConstraintViolation<UpdateChallengeRequest>> violations = validator.validate(request);

        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("수정필드가하나도없으면_검증에실패한다")
    void validate_whenNoFieldProvided_fails() {
        UpdateChallengeRequest request = new UpdateChallengeRequest(
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );

        Set<ConstraintViolation<UpdateChallengeRequest>> violations = validator.validate(request);

        assertThat(violations)
                .anySatisfy(violation -> assertThat(violation.getMessage()).isEqualTo("수정할 챌린지 정보가 하나 이상 필요합니다."));
    }

    @Test
    @DisplayName("종료일이시작일보다빠르면_검증에실패한다")
    void validate_whenEndedAtBeforeStartedAt_fails() {
        LocalDate startedAt = LocalDate.now().plusDays(10);
        UpdateChallengeRequest request = new UpdateChallengeRequest(
                null,
                null,
                null,
                null,
                startedAt,
                startedAt.minusDays(1),
                null
        );

        Set<ConstraintViolation<UpdateChallengeRequest>> violations = validator.validate(request);

        assertThat(violations)
                .anySatisfy(violation -> assertThat(violation.getMessage()).isEqualTo("종료일은 시작일보다 빠를 수 없습니다."));
    }

    @Test
    @DisplayName("제목이공백이면_검증에실패한다")
    void validate_whenTitleBlank_fails() {
        UpdateChallengeRequest request = new UpdateChallengeRequest(
                "   ",
                null,
                null,
                null,
                null,
                null,
                null
        );

        Set<ConstraintViolation<UpdateChallengeRequest>> violations = validator.validate(request);

        assertThat(violations)
                .anySatisfy(violation -> {
                    assertThat(violation.getPropertyPath()).hasToString("title");
                    assertThat(violation.getMessage()).isEqualTo("챌린지 이름은 공백만 입력할 수 없습니다.");
                });
    }

    @Test
    @DisplayName("최대참여인원이1이면_검증에실패한다")
    void validate_whenMaxMembersIsOne_fails() {
        UpdateChallengeRequest request = new UpdateChallengeRequest(
                null,
                null,
                null,
                1,
                null,
                null,
                null
        );

        Set<ConstraintViolation<UpdateChallengeRequest>> violations = validator.validate(request);

        assertThat(violations)
                .anySatisfy(violation -> {
                    assertThat(violation.getPropertyPath()).hasToString("maxMembers");
                    assertThat(violation.getMessage()).isEqualTo("최대 참여 인원은 2명 이상이어야 합니다.");
                });
    }
}
