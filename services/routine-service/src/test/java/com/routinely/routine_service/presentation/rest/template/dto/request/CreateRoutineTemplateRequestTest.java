package com.routinely.routine_service.presentation.rest.template.dto.request;

import com.routinely.routine_service.application.template.dto.CreateRoutineTemplateCommand;
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
    @DisplayName("빈도유형이유효하면_검증에성공한다")
    void validate_whenCountValid_succeeds() {
        CreateRoutineTemplateRequest request =
                new CreateRoutineTemplateRequest("아침 러닝 30분", "EXERCISE", "WEEKLY_COUNT", null, 3);

        Set<ConstraintViolation<CreateRoutineTemplateRequest>> violations = validator.validate(request);

        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("요일지정유형이유효하면_검증에성공한다")
    void validate_whenSpecificDaysValid_succeeds() {
        CreateRoutineTemplateRequest request = new CreateRoutineTemplateRequest(
                "아침 러닝 30분", "EXERCISE", "SPECIFIC_DAYS", List.of("MON", "WED", "FRI"), null);

        Set<ConstraintViolation<CreateRoutineTemplateRequest>> violations = validator.validate(request);

        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("DAILY는_요일횟수없이_검증에성공한다")
    void validate_whenDaily_succeeds() {
        CreateRoutineTemplateRequest request =
                new CreateRoutineTemplateRequest("아침 러닝 30분", "EXERCISE", "DAILY", null, null);

        Set<ConstraintViolation<CreateRoutineTemplateRequest>> violations = validator.validate(request);

        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("제목이공백이면_검증에실패한다")
    void validate_whenTitleBlank_fails() {
        CreateRoutineTemplateRequest request =
                new CreateRoutineTemplateRequest("   ", "EXERCISE", "DAILY", null, null);

        Set<ConstraintViolation<CreateRoutineTemplateRequest>> violations = validator.validate(request);

        assertThat(violations).anySatisfy(violation -> {
            assertThat(violation.getPropertyPath()).hasToString("title");
            assertThat(violation.getMessage()).isEqualTo("루틴 이름은 필수입니다.");
        });
    }

    @Test
    @DisplayName("반복유형이허용값이아니면_검증에실패한다")
    void validate_whenScheduleTypeInvalid_fails() {
        CreateRoutineTemplateRequest request =
                new CreateRoutineTemplateRequest("아침 러닝 30분", "EXERCISE", "YEARLY", null, null);

        Set<ConstraintViolation<CreateRoutineTemplateRequest>> violations = validator.validate(request);

        assertThat(violations).anySatisfy(violation -> {
            assertThat(violation.getPropertyPath()).hasToString("scheduleType");
            assertThat(violation.getMessage()).isEqualTo("올바르지 않은 반복 유형입니다.");
        });
    }

    @Test
    @DisplayName("빈도유형인데_목표횟수가없으면_검증에실패한다")
    void validate_whenCountMissingForCountType_fails() {
        CreateRoutineTemplateRequest request =
                new CreateRoutineTemplateRequest("아침 러닝 30분", "EXERCISE", "WEEKLY_COUNT", null, null);

        Set<ConstraintViolation<CreateRoutineTemplateRequest>> violations = validator.validate(request);

        assertThat(violations).anySatisfy(violation ->
                assertThat(violation.getMessage()).isEqualTo(ScheduleValidation.MESSAGE));
    }

    @Test
    @DisplayName("DAILY인데_목표횟수가있으면_검증에실패한다")
    void validate_whenCountGivenForDaily_fails() {
        CreateRoutineTemplateRequest request =
                new CreateRoutineTemplateRequest("아침 러닝 30분", "EXERCISE", "DAILY", null, 3);

        Set<ConstraintViolation<CreateRoutineTemplateRequest>> violations = validator.validate(request);

        assertThat(violations).anySatisfy(violation ->
                assertThat(violation.getMessage()).isEqualTo(ScheduleValidation.MESSAGE));
    }

    @Test
    @DisplayName("요일지정인데_요일이없으면_검증에실패한다")
    void validate_whenDaysMissingForSpecificDays_fails() {
        CreateRoutineTemplateRequest request =
                new CreateRoutineTemplateRequest("아침 러닝 30분", "EXERCISE", "SPECIFIC_DAYS", null, null);

        Set<ConstraintViolation<CreateRoutineTemplateRequest>> violations = validator.validate(request);

        assertThat(violations).anySatisfy(violation ->
                assertThat(violation.getMessage()).isEqualTo(ScheduleValidation.MESSAGE));
    }

    @Test
    @DisplayName("요일코드가유효하지않으면_검증에실패한다")
    void validate_whenInvalidDayCode_fails() {
        CreateRoutineTemplateRequest request = new CreateRoutineTemplateRequest(
                "아침 러닝 30분", "EXERCISE", "SPECIFIC_DAYS", List.of("MON", "FUNDAY"), null);

        Set<ConstraintViolation<CreateRoutineTemplateRequest>> violations = validator.validate(request);

        assertThat(violations).anySatisfy(violation ->
                assertThat(violation.getMessage()).isEqualTo(ScheduleValidation.MESSAGE));
    }

    @Test
    @DisplayName("목표횟수가1미만이면_검증에실패한다")
    void validate_whenTargetCountLessThanOne_fails() {
        CreateRoutineTemplateRequest request =
                new CreateRoutineTemplateRequest("아침 러닝 30분", "EXERCISE", "WEEKLY_COUNT", null, 0);

        Set<ConstraintViolation<CreateRoutineTemplateRequest>> violations = validator.validate(request);

        assertThat(violations).anySatisfy(violation -> {
            assertThat(violation.getPropertyPath()).hasToString("targetCount");
            assertThat(violation.getMessage()).isEqualTo("목표 횟수는 1 이상이어야 합니다.");
        });
    }

    @Test
    @DisplayName("toCommand는_빈도유형을enum으로변환하고_userId를담는다")
    void toCommand_convertsCountTypeAndCarriesUserId() {
        CreateRoutineTemplateRequest request =
                new CreateRoutineTemplateRequest("아침 러닝 30분", "EXERCISE", "WEEKLY_COUNT", null, 3);

        CreateRoutineTemplateCommand command = request.toCommand(1L);

        assertThat(command.userId()).isEqualTo(1L);
        assertThat(command.scheduleType()).isEqualTo(ScheduleType.WEEKLY_COUNT);
        assertThat(command.targetCount()).isEqualTo(3);
        assertThat(command.daysOfWeek()).isNull();
    }

    @Test
    @DisplayName("toCommand는_요일리스트를비트마스크로변환한다")
    void toCommand_convertsDaysToBitmask() {
        CreateRoutineTemplateRequest request = new CreateRoutineTemplateRequest(
                "아침 러닝 30분", "EXERCISE", "SPECIFIC_DAYS", List.of("MON", "WED", "FRI"), null);

        CreateRoutineTemplateCommand command = request.toCommand(1L);

        assertThat(command.scheduleType()).isEqualTo(ScheduleType.SPECIFIC_DAYS);
        assertThat(command.daysOfWeek()).isEqualTo((short) 0b0010101); // 월·수·금
        assertThat(command.targetCount()).isNull();
    }
}
