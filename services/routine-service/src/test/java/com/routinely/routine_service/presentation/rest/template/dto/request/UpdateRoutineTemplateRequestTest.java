package com.routinely.routine_service.presentation.rest.template.dto.request;

import com.routinely.routine_service.application.template.dto.UpdateRoutineTemplateCommand;
import com.routinely.routine_service.domain.template.ScheduleType;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
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
                new UpdateRoutineTemplateRequest("저녁 러닝 30분", null, null, null, null);

        Set<ConstraintViolation<UpdateRoutineTemplateRequest>> violations = validator.validate(request);

        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("모든필드가null이면_검증에실패한다")
    void validate_whenAllFieldsNull_fails() {
        UpdateRoutineTemplateRequest request =
                new UpdateRoutineTemplateRequest(null, null, null, null, null);

        Set<ConstraintViolation<UpdateRoutineTemplateRequest>> violations = validator.validate(request);

        assertThat(violations).anySatisfy(violation ->
                assertThat(violation.getMessage()).isEqualTo("수정할 루틴 템플릿 정보가 하나 이상 필요합니다."));
    }

    @Test
    @DisplayName("목표횟수를_유형없이단독으로보내면_검증에실패한다")
    void validate_whenTargetCountWithoutType_fails() {
        UpdateRoutineTemplateRequest request =
                new UpdateRoutineTemplateRequest(null, null, null, null, 3);

        Set<ConstraintViolation<UpdateRoutineTemplateRequest>> violations = validator.validate(request);

        assertThat(violations).anySatisfy(violation ->
                assertThat(violation.getMessage()).isEqualTo("요일/목표 횟수는 반복 유형과 함께 지정해야 합니다."));
    }

    @Test
    @DisplayName("요일을_유형없이단독으로보내면_검증에실패한다")
    void validate_whenDaysWithoutType_fails() {
        UpdateRoutineTemplateRequest request =
                new UpdateRoutineTemplateRequest(null, null, null, List.of("MON"), null);

        Set<ConstraintViolation<UpdateRoutineTemplateRequest>> violations = validator.validate(request);

        assertThat(violations).anySatisfy(violation ->
                assertThat(violation.getMessage()).isEqualTo("요일/목표 횟수는 반복 유형과 함께 지정해야 합니다."));
    }

    @Test
    @DisplayName("빈요일배열을_유형없이단독으로보내면_검증에실패한다(no-op방지)")
    void validate_whenEmptyDaysWithoutType_fails() {
        UpdateRoutineTemplateRequest request =
                new UpdateRoutineTemplateRequest(null, null, null, List.of(), null);

        Set<ConstraintViolation<UpdateRoutineTemplateRequest>> violations = validator.validate(request);

        assertThat(violations).anySatisfy(violation ->
                assertThat(violation.getMessage()).isEqualTo("요일/목표 횟수는 반복 유형과 함께 지정해야 합니다."));
    }

    @Test
    @DisplayName("빈도유형인데_목표횟수가없으면_검증에실패한다")
    void validate_whenCountMissingForCountType_fails() {
        UpdateRoutineTemplateRequest request =
                new UpdateRoutineTemplateRequest(null, null, "WEEKLY_COUNT", null, null);

        Set<ConstraintViolation<UpdateRoutineTemplateRequest>> violations = validator.validate(request);

        assertThat(violations).anySatisfy(violation ->
                assertThat(violation.getMessage()).isEqualTo(ScheduleValidation.MESSAGE));
    }

    @Test
    @DisplayName("유형과목표횟수를쌍으로보내면_검증에성공한다")
    void validate_whenCountPairProvided_succeeds() {
        UpdateRoutineTemplateRequest request =
                new UpdateRoutineTemplateRequest(null, null, "MONTHLY_COUNT", null, 10);

        Set<ConstraintViolation<UpdateRoutineTemplateRequest>> violations = validator.validate(request);

        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("유형과요일을쌍으로보내면_검증에성공한다")
    void validate_whenSpecificDaysPairProvided_succeeds() {
        UpdateRoutineTemplateRequest request =
                new UpdateRoutineTemplateRequest(null, null, "SPECIFIC_DAYS", List.of("MON", "WED"), null);

        Set<ConstraintViolation<UpdateRoutineTemplateRequest>> violations = validator.validate(request);

        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("제목이공백만이면_검증에실패한다")
    void validate_whenTitleBlank_fails() {
        UpdateRoutineTemplateRequest request =
                new UpdateRoutineTemplateRequest("   ", null, null, null, null);

        Set<ConstraintViolation<UpdateRoutineTemplateRequest>> violations = validator.validate(request);

        assertThat(violations).anySatisfy(violation -> {
            assertThat(violation.getPropertyPath()).hasToString("title");
            assertThat(violation.getMessage()).isEqualTo("루틴 이름은 공백만 입력할 수 없습니다.");
        });
    }

    @Test
    @DisplayName("toCommand는_null유형은null로_유효한유형은enum과비트마스크로변환한다")
    void toCommand_convertsScheduleNullSafely() {
        UpdateRoutineTemplateCommand withoutType =
                new UpdateRoutineTemplateRequest("저녁 러닝 30분", null, null, null, null).toCommand(10L, 1L);
        UpdateRoutineTemplateCommand withDays =
                new UpdateRoutineTemplateRequest(null, null, "SPECIFIC_DAYS", List.of("MON", "WED", "FRI"), null)
                        .toCommand(10L, 1L);

        assertThat(withoutType.templateId()).isEqualTo(10L);
        assertThat(withoutType.userId()).isEqualTo(1L);
        assertThat(withoutType.scheduleType()).isNull();
        assertThat(withDays.scheduleType()).isEqualTo(ScheduleType.SPECIFIC_DAYS);
        assertThat(withDays.daysOfWeek()).isEqualTo((short) 0b0010101); // 월·수·금
        assertThat(withDays.targetCount()).isNull();
    }
}
