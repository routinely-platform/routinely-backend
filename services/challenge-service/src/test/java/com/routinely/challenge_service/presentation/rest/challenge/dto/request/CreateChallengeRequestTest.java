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

@DisplayName("CreateChallengeRequest")
class CreateChallengeRequestTest {

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
    @DisplayName("모든필드가유효하면_검증에성공한다")
    void validate_whenAllFieldsValid_succeeds() {
        CreateChallengeRequest request = validRequest();

        Set<ConstraintViolation<CreateChallengeRequest>> violations = validator.validate(request);

        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("제목이공백이면_검증에실패한다")
    void validate_whenTitleBlank_fails() {
        CreateChallengeRequest request = new CreateChallengeRequest(
                "   ",
                "매일 달리기",
                true,
                10,
                "EXERCISE",
                LocalDate.now().plusDays(1),
                LocalDate.now().plusDays(30),
                "아침 러닝 30분",
                "DAILY",
                null
        );

        Set<ConstraintViolation<CreateChallengeRequest>> violations = validator.validate(request);

        assertThat(violations)
                .anySatisfy(violation -> {
                    assertThat(violation.getPropertyPath()).hasToString("title");
                    assertThat(violation.getMessage()).isEqualTo("챌린지 이름은 필수입니다.");
                });
    }

    @Test
    @DisplayName("공개여부가null이면_검증에실패한다")
    void validate_whenIsPublicNull_fails() {
        CreateChallengeRequest request = new CreateChallengeRequest(
                "30일 러닝 챌린지",
                "매일 달리기",
                null,
                10,
                "EXERCISE",
                LocalDate.now().plusDays(1),
                LocalDate.now().plusDays(30),
                "아침 러닝 30분",
                "DAILY",
                null
        );

        Set<ConstraintViolation<CreateChallengeRequest>> violations = validator.validate(request);

        assertThat(violations)
                .anySatisfy(violation -> {
                    assertThat(violation.getPropertyPath()).hasToString("isPublic");
                    assertThat(violation.getMessage()).isEqualTo("공개 여부는 필수입니다.");
                });
    }

    @Test
    @DisplayName("최대참여인원이1이면_검증에실패한다")
    void validate_whenMaxMembersIsOne_fails() {
        CreateChallengeRequest request = new CreateChallengeRequest(
                "30일 러닝 챌린지",
                "매일 달리기",
                true,
                1,
                "EXERCISE",
                LocalDate.now().plusDays(1),
                LocalDate.now().plusDays(30),
                "아침 러닝 30분",
                "DAILY",
                null
        );

        Set<ConstraintViolation<CreateChallengeRequest>> violations = validator.validate(request);

        assertThat(violations)
                .anySatisfy(violation -> {
                    assertThat(violation.getPropertyPath()).hasToString("maxMembers");
                    assertThat(violation.getMessage()).isEqualTo("최대 참여 인원은 2명 이상이어야 합니다.");
                });
    }

    @Test
    @DisplayName("카테고리코드가30자를초과하면_검증에실패한다")
    void validate_whenCategoryCodeExceeds30Characters_fails() {
        CreateChallengeRequest request = new CreateChallengeRequest(
                "30일 러닝 챌린지",
                "매일 달리기",
                true,
                10,
                "A".repeat(31),
                LocalDate.now().plusDays(1),
                LocalDate.now().plusDays(30),
                "아침 러닝 30분",
                "DAILY",
                null
        );

        Set<ConstraintViolation<CreateChallengeRequest>> violations = validator.validate(request);

        assertThat(violations)
                .anySatisfy(violation -> {
                    assertThat(violation.getPropertyPath()).hasToString("categoryCode");
                    assertThat(violation.getMessage()).isEqualTo("카테고리 코드는 30자 이하여야 합니다.");
                });
    }

    @Test
    @DisplayName("시작일이과거이면_검증에실패한다")
    void validate_whenStartedAtInPast_fails() {
        CreateChallengeRequest request = new CreateChallengeRequest(
                "30일 러닝 챌린지",
                "매일 달리기",
                true,
                10,
                "EXERCISE",
                LocalDate.now().minusDays(1),
                LocalDate.now().plusDays(30),
                "아침 러닝 30분",
                "DAILY",
                null
        );

        Set<ConstraintViolation<CreateChallengeRequest>> violations = validator.validate(request);

        assertThat(violations)
                .anySatisfy(violation -> {
                    assertThat(violation.getPropertyPath()).hasToString("startedAt");
                    assertThat(violation.getMessage()).isEqualTo("시작일은 오늘 이후여야 합니다.");
                });
    }

    @Test
    @DisplayName("종료일이시작일보다빠르면_검증에실패한다")
    void validate_whenEndedAtBeforeStartedAt_fails() {
        LocalDate startedAt = LocalDate.now().plusDays(10);
        CreateChallengeRequest request = new CreateChallengeRequest(
                "30일 러닝 챌린지",
                "매일 달리기",
                true,
                10,
                "EXERCISE",
                startedAt,
                startedAt.minusDays(1),
                "아침 러닝 30분",
                "DAILY",
                null
        );

        Set<ConstraintViolation<CreateChallengeRequest>> violations = validator.validate(request);

        assertThat(violations)
                .anySatisfy(violation -> assertThat(violation.getMessage()).isEqualTo("종료일은 시작일보다 빠를 수 없습니다."));
    }

    @Test
    @DisplayName("루틴이름이공백이면_검증에실패한다")
    void validate_whenRoutineTitleBlank_fails() {
        CreateChallengeRequest request = new CreateChallengeRequest(
                "30일 러닝 챌린지",
                "매일 달리기",
                true,
                10,
                "EXERCISE",
                LocalDate.now().plusDays(1),
                LocalDate.now().plusDays(30),
                "   ",
                "DAILY",
                null
        );

        Set<ConstraintViolation<CreateChallengeRequest>> violations = validator.validate(request);

        assertThat(violations)
                .anySatisfy(violation -> {
                    assertThat(violation.getPropertyPath()).hasToString("routineTitle");
                    assertThat(violation.getMessage()).isEqualTo("루틴 이름은 필수입니다.");
                });
    }

    @Test
    @DisplayName("반복유형이공백이면_검증에실패한다")
    void validate_whenRepeatTypeBlank_fails() {
        CreateChallengeRequest request = new CreateChallengeRequest(
                "30일 러닝 챌린지",
                "매일 달리기",
                true,
                10,
                "EXERCISE",
                LocalDate.now().plusDays(1),
                LocalDate.now().plusDays(30),
                "아침 러닝 30분",
                "   ",
                null
        );

        Set<ConstraintViolation<CreateChallengeRequest>> violations = validator.validate(request);

        assertThat(violations)
                .anySatisfy(violation -> {
                    assertThat(violation.getPropertyPath()).hasToString("repeatType");
                    assertThat(violation.getMessage()).isEqualTo("반복 유형은 필수입니다.");
                });
    }

    @Test
    @DisplayName("반복유형이유효하지않으면_검증에실패한다")
    void validate_whenRepeatTypeInvalid_fails() {
        CreateChallengeRequest request = new CreateChallengeRequest(
                "30일 러닝 챌린지",
                "매일 달리기",
                true,
                10,
                "EXERCISE",
                LocalDate.now().plusDays(1),
                LocalDate.now().plusDays(30),
                "아침 러닝 30분",
                "INVALID_TYPE",
                null
        );

        Set<ConstraintViolation<CreateChallengeRequest>> violations = validator.validate(request);

        assertThat(violations)
                .anySatisfy(violation -> {
                    assertThat(violation.getPropertyPath()).hasToString("repeatType");
                    assertThat(violation.getMessage()).isEqualTo("올바르지 않은 반복 유형입니다.");
                });
    }

    @Test
    @DisplayName("WEEKLY_N인데반복횟수가없으면_검증에실패한다")
    void validate_whenWeeklyNWithoutRepeatValue_fails() {
        CreateChallengeRequest request = new CreateChallengeRequest(
                "30일 러닝 챌린지",
                "매일 달리기",
                true,
                10,
                "EXERCISE",
                LocalDate.now().plusDays(1),
                LocalDate.now().plusDays(30),
                "아침 러닝 30분",
                "WEEKLY_N",
                null
        );

        Set<ConstraintViolation<CreateChallengeRequest>> violations = validator.validate(request);

        assertThat(violations)
                .anySatisfy(violation -> {
                    assertThat(violation.getPropertyPath()).hasToString("repeatValueValid");
                    assertThat(violation.getMessage())
                            .isEqualTo("반복 횟수는 DAILY_N/WEEKLY_N/MONTHLY_N에서만 지정할 수 있으며, 해당 유형에서는 필수입니다.");
                });
    }

    @Test
    @DisplayName("DAILY_N인데반복횟수가없으면_검증에실패한다")
    void validate_whenDailyNWithoutRepeatValue_fails() {
        CreateChallengeRequest request = new CreateChallengeRequest(
                "30일 러닝 챌린지",
                "매일 달리기",
                true,
                10,
                "EXERCISE",
                LocalDate.now().plusDays(1),
                LocalDate.now().plusDays(30),
                "아침 러닝 30분",
                "DAILY_N",
                null
        );

        Set<ConstraintViolation<CreateChallengeRequest>> violations = validator.validate(request);

        assertThat(violations)
                .anySatisfy(violation -> {
                    assertThat(violation.getPropertyPath()).hasToString("repeatValueValid");
                    assertThat(violation.getMessage())
                            .isEqualTo("반복 횟수는 DAILY_N/WEEKLY_N/MONTHLY_N에서만 지정할 수 있으며, 해당 유형에서는 필수입니다.");
                });
    }

    @Test
    @DisplayName("DAILY_N에반복횟수가있으면_검증에성공한다")
    void validate_whenDailyNWithRepeatValue_succeeds() {
        CreateChallengeRequest request = new CreateChallengeRequest(
                "30일 러닝 챌린지",
                "매일 달리기",
                true,
                10,
                "EXERCISE",
                LocalDate.now().plusDays(1),
                LocalDate.now().plusDays(30),
                "아침 러닝 30분",
                "DAILY_N",
                3
        );

        Set<ConstraintViolation<CreateChallengeRequest>> violations = validator.validate(request);

        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("WEEKLY_N인데반복횟수가0이면_검증에실패한다")
    void validate_whenWeeklyNWithNonPositiveRepeatValue_fails() {
        CreateChallengeRequest request = new CreateChallengeRequest(
                "30일 러닝 챌린지",
                "매일 달리기",
                true,
                10,
                "EXERCISE",
                LocalDate.now().plusDays(1),
                LocalDate.now().plusDays(30),
                "아침 러닝 30분",
                "WEEKLY_N",
                0
        );

        Set<ConstraintViolation<CreateChallengeRequest>> violations = validator.validate(request);

        assertThat(violations)
                .anySatisfy(violation -> {
                    assertThat(violation.getPropertyPath()).hasToString("repeatValue");
                    assertThat(violation.getMessage()).isEqualTo("반복 횟수는 1 이상이어야 합니다.");
                });
    }

    @Test
    @DisplayName("DAILY인데반복횟수가있으면_검증에실패한다")
    void validate_whenDailyWithRepeatValue_fails() {
        CreateChallengeRequest request = new CreateChallengeRequest(
                "30일 러닝 챌린지",
                "매일 달리기",
                true,
                10,
                "EXERCISE",
                LocalDate.now().plusDays(1),
                LocalDate.now().plusDays(30),
                "아침 러닝 30분",
                "DAILY",
                3
        );

        Set<ConstraintViolation<CreateChallengeRequest>> violations = validator.validate(request);

        assertThat(violations)
                .anySatisfy(violation -> assertThat(violation.getPropertyPath()).hasToString("repeatValueValid"));
    }

    private CreateChallengeRequest validRequest() {
        return new CreateChallengeRequest(
                "30일 러닝 챌린지",
                "매일 달리기",
                true,
                10,
                "EXERCISE",
                LocalDate.now().plusDays(1),
                LocalDate.now().plusDays(30),
                "아침 러닝 30분",
                "DAILY",
                null
        );
    }
}
