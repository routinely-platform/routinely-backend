package com.routinely.routine_service.presentation.rest.template.dto.request;

import com.routinely.routine_service.application.template.dto.UpdateRoutineTemplateCommand;
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

@DisplayName("UpdateRoutineTemplateRequest")
class UpdateRoutineTemplateRequestTest {

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
    @DisplayName("제목만수정해도_검증에성공한다")
    void validate_whenTitleOnly_succeeds() {
        UpdateRoutineTemplateRequest request =
                new UpdateRoutineTemplateRequest("저녁 러닝 30분", null, null, null);

        Set<ConstraintViolation<UpdateRoutineTemplateRequest>> violations = validator.validate(request);

        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("모든필드가null이면_검증에실패한다")
    void validate_whenAllFieldsNull_fails() {
        UpdateRoutineTemplateRequest request =
                new UpdateRoutineTemplateRequest(null, null, null, null);

        Set<ConstraintViolation<UpdateRoutineTemplateRequest>> violations = validator.validate(request);

        assertThat(violations).anySatisfy(violation ->
                assertThat(violation.getMessage()).isEqualTo("수정할 루틴 템플릿 정보가 하나 이상 필요합니다."));
    }

    @Test
    @DisplayName("반복횟수를_반복유형없이단독으로보내면_검증에실패한다")
    void validate_whenRepeatValueWithoutType_fails() {
        UpdateRoutineTemplateRequest request =
                new UpdateRoutineTemplateRequest(null, null, null, 3);

        Set<ConstraintViolation<UpdateRoutineTemplateRequest>> violations = validator.validate(request);

        assertThat(violations).anySatisfy(violation ->
                assertThat(violation.getMessage()).isEqualTo("반복 횟수는 반복 유형과 함께 지정해야 합니다."));
    }

    @Test
    @DisplayName("반복횟수필수유형인데_반복횟수가없으면_검증에실패한다")
    void validate_whenRepeatValueMissingForNType_fails() {
        UpdateRoutineTemplateRequest request =
                new UpdateRoutineTemplateRequest(null, null, "WEEKLY_N", null);

        Set<ConstraintViolation<UpdateRoutineTemplateRequest>> violations = validator.validate(request);

        assertThat(violations).anySatisfy(violation ->
                assertThat(violation.getMessage())
                        .isEqualTo("반복 횟수는 DAILY_N/WEEKLY_N/MONTHLY_N에서만 지정할 수 있으며, 해당 유형에서는 필수입니다."));
    }

    @Test
    @DisplayName("반복유형과반복횟수를쌍으로보내면_검증에성공한다")
    void validate_whenRepeatPairProvided_succeeds() {
        UpdateRoutineTemplateRequest request =
                new UpdateRoutineTemplateRequest(null, null, "MONTHLY_N", 10);

        Set<ConstraintViolation<UpdateRoutineTemplateRequest>> violations = validator.validate(request);

        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("제목이공백만이면_검증에실패한다")
    void validate_whenTitleBlank_fails() {
        UpdateRoutineTemplateRequest request =
                new UpdateRoutineTemplateRequest("   ", null, null, null);

        Set<ConstraintViolation<UpdateRoutineTemplateRequest>> violations = validator.validate(request);

        assertThat(violations).anySatisfy(violation -> {
            assertThat(violation.getPropertyPath()).hasToString("title");
            assertThat(violation.getMessage()).isEqualTo("루틴 이름은 공백만 입력할 수 없습니다.");
        });
    }

    @Test
    @DisplayName("toCommand는_null유형은null로_유효한유형은enum으로변환한다")
    void toCommand_convertsRepeatTypeNullSafely() {
        UpdateRoutineTemplateCommand withoutType =
                new UpdateRoutineTemplateRequest("저녁 러닝 30분", null, null, null).toCommand(10L, 1L);
        UpdateRoutineTemplateCommand withType =
                new UpdateRoutineTemplateRequest(null, null, "DAILY_N", 2).toCommand(10L, 1L);

        assertThat(withoutType.templateId()).isEqualTo(10L);
        assertThat(withoutType.userId()).isEqualTo(1L);
        assertThat(withoutType.repeatType()).isNull();
        assertThat(withType.repeatType()).isEqualTo(RepeatType.DAILY_N);
        assertThat(withType.repeatValue()).isEqualTo(2);
    }
}
