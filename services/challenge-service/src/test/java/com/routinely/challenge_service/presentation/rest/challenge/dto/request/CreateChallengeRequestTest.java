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
import java.util.List;
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
                null,
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
                null,
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
    @DisplayName("비공개챌린지이면_검증에실패한다")
    void validate_whenPrivateChallenge_fails() {
        CreateChallengeRequest request = new CreateChallengeRequest(
                "30일 러닝 챌린지",
                "매일 달리기",
                false,
                10,
                "EXERCISE",
                LocalDate.now().plusDays(1),
                LocalDate.now().plusDays(30),
                "아침 러닝 30분",
                "DAILY",
                null,
                null
        );

        Set<ConstraintViolation<CreateChallengeRequest>> violations = validator.validate(request);

        assertThat(violations)
                .anySatisfy(violation -> {
                    assertThat(violation.getPropertyPath()).hasToString("publicChallenge");
                    assertThat(violation.getMessage()).isEqualTo("MVP에서는 공개 챌린지만 만들 수 있습니다.");
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
                null,
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
    @DisplayName("최대참여인원이20명이면_검증에성공한다")
    void validate_whenMaxMembersIsTwenty_succeeds() {
        CreateChallengeRequest request = new CreateChallengeRequest(
                "30일 러닝 챌린지",
                "매일 달리기",
                true,
                20,
                "EXERCISE",
                LocalDate.now().plusDays(1),
                LocalDate.now().plusDays(30),
                "아침 러닝 30분",
                "DAILY",
                null,
                null
        );

        Set<ConstraintViolation<CreateChallengeRequest>> violations = validator.validate(request);

        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("최대참여인원이21명이면_검증에실패한다")
    void validate_whenMaxMembersExceedsTwenty_fails() {
        CreateChallengeRequest request = new CreateChallengeRequest(
                "30일 러닝 챌린지",
                "매일 달리기",
                true,
                21,
                "EXERCISE",
                LocalDate.now().plusDays(1),
                LocalDate.now().plusDays(30),
                "아침 러닝 30분",
                "DAILY",
                null,
                null
        );

        Set<ConstraintViolation<CreateChallengeRequest>> violations = validator.validate(request);

        assertThat(violations)
                .anySatisfy(violation -> {
                    assertThat(violation.getPropertyPath()).hasToString("maxMembers");
                    assertThat(violation.getMessage()).isEqualTo("최대 참여 인원은 20명 이하여야 합니다.");
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
                null,
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
                null,
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
                null,
                null
        );

        Set<ConstraintViolation<CreateChallengeRequest>> violations = validator.validate(request);

        assertThat(violations)
                .anySatisfy(violation -> assertThat(violation.getMessage()).isEqualTo("종료일은 시작일보다 빠를 수 없습니다."));
    }

    @Test
    @DisplayName("기간이100일이면_검증에성공한다")
    void validate_whenPeriodIsOneHundredDays_succeeds() {
        LocalDate startedAt = LocalDate.now().plusDays(1);
        CreateChallengeRequest request = new CreateChallengeRequest(
                "100일 챌린지",
                "매일 달리기",
                true,
                10,
                "EXERCISE",
                startedAt,
                startedAt.plusDays(99),  // 시작일 포함 100일
                "아침 러닝 30분",
                "DAILY",
                null,
                null
        );

        Set<ConstraintViolation<CreateChallengeRequest>> violations = validator.validate(request);

        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("기간이101일이면_검증에실패한다")
    void validate_whenPeriodExceedsOneHundredDays_fails() {
        LocalDate startedAt = LocalDate.now().plusDays(1);
        CreateChallengeRequest request = new CreateChallengeRequest(
                "101일 챌린지",
                "매일 달리기",
                true,
                10,
                "EXERCISE",
                startedAt,
                startedAt.plusDays(100),  // 시작일 포함 101일
                "아침 러닝 30분",
                "DAILY",
                null,
                null
        );

        Set<ConstraintViolation<CreateChallengeRequest>> violations = validator.validate(request);

        assertThat(violations)
                .anySatisfy(violation -> {
                    assertThat(violation.getPropertyPath()).hasToString("periodWithinLimit");
                    assertThat(violation.getMessage()).isEqualTo("챌린지 기간은 100일 이하여야 합니다.");
                });
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
                null,
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
    void validate_whenScheduleTypeBlank_fails() {
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
                null,
                null
        );

        Set<ConstraintViolation<CreateChallengeRequest>> violations = validator.validate(request);

        assertThat(violations)
                .anySatisfy(violation -> {
                    assertThat(violation.getPropertyPath()).hasToString("scheduleType");
                    assertThat(violation.getMessage()).isEqualTo("반복 유형은 필수입니다.");
                });
    }

    @Test
    @DisplayName("반복유형이유효하지않으면_검증에실패한다")
    void validate_whenScheduleTypeInvalid_fails() {
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
                null,
                null
        );

        Set<ConstraintViolation<CreateChallengeRequest>> violations = validator.validate(request);

        assertThat(violations)
                .anySatisfy(violation -> {
                    assertThat(violation.getPropertyPath()).hasToString("scheduleType");
                    assertThat(violation.getMessage()).isEqualTo("올바르지 않은 반복 유형입니다.");
                });
    }

    @Test
    @DisplayName("요일지정유형(SPECIFIC_DAYS)에요일이있으면_검증에성공한다")
    void validate_whenSpecificDaysWithDays_succeeds() {
        CreateChallengeRequest request = scheduleRequest("SPECIFIC_DAYS", List.of("MON", "WED", "FRI"), null);

        assertThat(validator.validate(request)).isEmpty();
        assertThat(request.toCommand().daysOfWeek()).isEqualTo((short) 21);
    }

    @Test
    @DisplayName("SPECIFIC_DAYS인데요일이없으면_검증에실패한다")
    void validate_whenSpecificDaysWithoutDays_fails() {
        CreateChallengeRequest request = scheduleRequest("SPECIFIC_DAYS", null, null);

        assertThat(validator.validate(request))
                .anySatisfy(violation -> assertThat(violation.getPropertyPath()).hasToString("daysOfWeekValid"));
    }

    @Test
    @DisplayName("SPECIFIC_DAYS인데요일이0개이면_검증에실패한다")
    void validate_whenSpecificDaysWithEmptyDays_fails() {
        CreateChallengeRequest request = scheduleRequest("SPECIFIC_DAYS", List.of(), null);

        assertThat(validator.validate(request))
                .anySatisfy(violation -> assertThat(violation.getPropertyPath()).hasToString("daysOfWeekValid"));
    }

    @Test
    @DisplayName("SPECIFIC_DAYS에잘못된요일코드가있으면_검증에실패한다")
    void validate_whenSpecificDaysWithInvalidCode_fails() {
        CreateChallengeRequest request = scheduleRequest("SPECIFIC_DAYS", List.of("MON", "FUNDAY"), null);

        assertThat(validator.validate(request))
                .anySatisfy(violation -> assertThat(violation.getPropertyPath()).hasToString("daysOfWeekValid"));
    }

    @Test
    @DisplayName("DAILY인데요일이있으면_검증에실패한다")
    void validate_whenDailyWithDays_fails() {
        CreateChallengeRequest request = scheduleRequest("DAILY", List.of("MON"), null);

        assertThat(validator.validate(request))
                .anySatisfy(violation -> assertThat(violation.getPropertyPath()).hasToString("daysOfWeekValid"));
    }

    @Test
    @DisplayName("SPECIFIC_DAYS에목표횟수가있으면_검증에실패한다")
    void validate_whenSpecificDaysWithTargetCount_fails() {
        CreateChallengeRequest request = scheduleRequest("SPECIFIC_DAYS", List.of("MON"), 3);

        assertThat(validator.validate(request))
                .anySatisfy(violation -> assertThat(violation.getPropertyPath()).hasToString("targetCountValid"));
    }

    @Test
    @DisplayName("주6회와월28회는_검증에성공한다")
    void validate_whenTargetCountAtUpperBound_succeeds() {
        assertThat(validator.validate(scheduleRequest("WEEKLY_COUNT", null, 6))).isEmpty();
        assertThat(validator.validate(scheduleRequest("MONTHLY_COUNT", null, 28))).isEmpty();
    }

    @Test
    @DisplayName("주7회와월29회는_검증에실패한다")
    void validate_whenTargetCountOverUpperBound_fails() {
        assertThat(validator.validate(scheduleRequest("WEEKLY_COUNT", null, 7)))
                .anySatisfy(violation -> assertThat(violation.getPropertyPath()).hasToString("targetCountWithinLimit"));
        assertThat(validator.validate(scheduleRequest("MONTHLY_COUNT", null, 29)))
                .anySatisfy(violation -> assertThat(violation.getPropertyPath()).hasToString("targetCountWithinLimit"));
    }

    @Test
    @DisplayName("WEEKLY_COUNT인데목표횟수가없으면_검증에실패한다")
    void validate_whenWeeklyCountWithoutTargetCount_fails() {
        CreateChallengeRequest request = new CreateChallengeRequest(
                "30일 러닝 챌린지",
                "매일 달리기",
                true,
                10,
                "EXERCISE",
                LocalDate.now().plusDays(1),
                LocalDate.now().plusDays(30),
                "아침 러닝 30분",
                "WEEKLY_COUNT",
                null,
                null
        );

        Set<ConstraintViolation<CreateChallengeRequest>> violations = validator.validate(request);

        assertThat(violations)
                .anySatisfy(violation -> {
                    assertThat(violation.getPropertyPath()).hasToString("targetCountValid");
                    assertThat(violation.getMessage())
                            .isEqualTo("목표 횟수는 WEEKLY_COUNT/MONTHLY_COUNT에서만 지정할 수 있으며, 해당 유형에서는 필수입니다.");
                });
    }

    @Test
    @DisplayName("MONTHLY_COUNT에목표횟수가있으면_검증에성공한다")
    void validate_whenMonthlyCountWithTargetCount_succeeds() {
        CreateChallengeRequest request = new CreateChallengeRequest(
                "30일 러닝 챌린지",
                "매일 달리기",
                true,
                10,
                "EXERCISE",
                LocalDate.now().plusDays(1),
                LocalDate.now().plusDays(30),
                "아침 러닝 30분",
                "MONTHLY_COUNT",
                null,
                3
        );

        Set<ConstraintViolation<CreateChallengeRequest>> violations = validator.validate(request);

        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("WEEKLY_COUNT인데목표횟수가0이면_검증에실패한다")
    void validate_whenWeeklyCountWithNonPositiveTargetCount_fails() {
        CreateChallengeRequest request = new CreateChallengeRequest(
                "30일 러닝 챌린지",
                "매일 달리기",
                true,
                10,
                "EXERCISE",
                LocalDate.now().plusDays(1),
                LocalDate.now().plusDays(30),
                "아침 러닝 30분",
                "WEEKLY_COUNT",
                null,
                0
        );

        Set<ConstraintViolation<CreateChallengeRequest>> violations = validator.validate(request);

        assertThat(violations)
                .anySatisfy(violation -> {
                    assertThat(violation.getPropertyPath()).hasToString("targetCount");
                    assertThat(violation.getMessage()).isEqualTo("목표 횟수는 1 이상이어야 합니다.");
                });
    }

    @Test
    @DisplayName("DAILY인데목표횟수가있으면_검증에실패한다")
    void validate_whenDailyWithTargetCount_fails() {
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
                null,
                3
        );

        Set<ConstraintViolation<CreateChallengeRequest>> violations = validator.validate(request);

        assertThat(violations)
                .anySatisfy(violation -> assertThat(violation.getPropertyPath()).hasToString("targetCountValid"));
    }

    private CreateChallengeRequest scheduleRequest(String scheduleType, List<String> daysOfWeek, Integer targetCount) {
        return new CreateChallengeRequest(
                "30일 러닝 챌린지",
                "매일 달리기",
                true,
                10,
                "EXERCISE",
                LocalDate.now().plusDays(1),
                LocalDate.now().plusDays(30),
                "아침 러닝 30분",
                scheduleType,
                daysOfWeek,
                targetCount
        );
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
                null,
                null
        );
    }
}
