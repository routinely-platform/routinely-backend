package com.routinely.routine_service.presentation.rest.template.dto.request;

import com.routinely.routine_service.application.template.dto.CreateRoutineTemplateCommand;
import com.routinely.routine_service.domain.template.RepeatType;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("CreateRoutineTemplateRequest")
class CreateRoutineTemplateRequestTest {

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
        CreateRoutineTemplateRequest request =
                new CreateRoutineTemplateRequest("아침 러닝 30분", "EXERCISE", "WEEKLY_N", 3);

        Set<ConstraintViolation<CreateRoutineTemplateRequest>> violations = validator.validate(request);

        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("제목이공백이면_검증에실패한다")
    void validate_whenTitleBlank_fails() {
        CreateRoutineTemplateRequest request =
                new CreateRoutineTemplateRequest("   ", "EXERCISE", "DAILY", null);

        Set<ConstraintViolation<CreateRoutineTemplateRequest>> violations = validator.validate(request);

        assertThat(violations).anySatisfy(violation -> {
            assertThat(violation.getPropertyPath()).hasToString("title");
            assertThat(violation.getMessage()).isEqualTo("루틴 이름은 필수입니다.");
        });
    }

    @Test
    @DisplayName("반복유형이허용값이아니면_검증에실패한다")
    void validate_whenRepeatTypeInvalid_fails() {
        CreateRoutineTemplateRequest request =
                new CreateRoutineTemplateRequest("아침 러닝 30분", "EXERCISE", "YEARLY", null);

        Set<ConstraintViolation<CreateRoutineTemplateRequest>> violations = validator.validate(request);

        assertThat(violations).anySatisfy(violation -> {
            assertThat(violation.getPropertyPath()).hasToString("repeatType");
            assertThat(violation.getMessage()).isEqualTo("올바르지 않은 반복 유형입니다.");
        });
    }

    @Test
    @DisplayName("반복횟수필수유형인데_반복횟수가없으면_검증에실패한다")
    void validate_whenRepeatValueMissingForNType_fails() {
        CreateRoutineTemplateRequest request =
                new CreateRoutineTemplateRequest("아침 러닝 30분", "EXERCISE", "DAILY_N", null);

        Set<ConstraintViolation<CreateRoutineTemplateRequest>> violations = validator.validate(request);

        assertThat(violations).anySatisfy(violation ->
                assertThat(violation.getMessage())
                        .isEqualTo("반복 횟수는 DAILY_N/WEEKLY_N/MONTHLY_N에서만 지정할 수 있으며, 해당 유형에서는 필수입니다."));
    }

    @Test
    @DisplayName("반복횟수없는유형인데_반복횟수가있으면_검증에실패한다")
    void validate_whenRepeatValueGivenForDaily_fails() {
        CreateRoutineTemplateRequest request =
                new CreateRoutineTemplateRequest("아침 러닝 30분", "EXERCISE", "DAILY", 3);

        Set<ConstraintViolation<CreateRoutineTemplateRequest>> violations = validator.validate(request);

        assertThat(violations).anySatisfy(violation ->
                assertThat(violation.getMessage())
                        .isEqualTo("반복 횟수는 DAILY_N/WEEKLY_N/MONTHLY_N에서만 지정할 수 있으며, 해당 유형에서는 필수입니다."));
    }

    @Test
    @DisplayName("반복횟수가1미만이면_검증에실패한다")
    void validate_whenRepeatValueLessThanOne_fails() {
        CreateRoutineTemplateRequest request =
                new CreateRoutineTemplateRequest("아침 러닝 30분", "EXERCISE", "WEEKLY_N", 0);

        Set<ConstraintViolation<CreateRoutineTemplateRequest>> violations = validator.validate(request);

        assertThat(violations).anySatisfy(violation -> {
            assertThat(violation.getPropertyPath()).hasToString("repeatValue");
            assertThat(violation.getMessage()).isEqualTo("반복 횟수는 1 이상이어야 합니다.");
        });
    }

    @Test
    @DisplayName("toCommand는_반복유형을enum으로변환하고_userId를담는다")
    void toCommand_convertsRepeatTypeAndCarriesUserId() {
        CreateRoutineTemplateRequest request =
                new CreateRoutineTemplateRequest("아침 러닝 30분", "EXERCISE", "WEEKLY_N", 3);

        CreateRoutineTemplateCommand command = request.toCommand(1L);

        assertThat(command.userId()).isEqualTo(1L);
        assertThat(command.repeatType()).isEqualTo(RepeatType.WEEKLY_N);
        assertThat(command.repeatValue()).isEqualTo(3);
    }
}
