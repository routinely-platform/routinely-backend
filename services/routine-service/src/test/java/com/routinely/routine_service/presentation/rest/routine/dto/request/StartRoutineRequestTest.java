package com.routinely.routine_service.presentation.rest.routine.dto.request;

import com.routinely.routine_service.application.routine.dto.StartRoutineCommand;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("StartRoutineRequest")
class StartRoutineRequestTest {

    private static final LocalDate START = LocalDate.of(2026, 2, 1);
    private static final LocalDate END = LocalDate.of(2026, 3, 2);

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
        StartRoutineRequest request = new StartRoutineRequest(1L, START, END, "07:00:00");

        Set<ConstraintViolation<StartRoutineRequest>> violations = validator.validate(request);

        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("선호시각이null이어도_검증에성공한다")
    void validate_whenPreferredTimeNull_succeeds() {
        StartRoutineRequest request = new StartRoutineRequest(1L, START, END, null);

        Set<ConstraintViolation<StartRoutineRequest>> violations = validator.validate(request);

        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("루틴템플릿ID가null이면_검증에실패한다")
    void validate_whenTemplateIdNull_fails() {
        StartRoutineRequest request = new StartRoutineRequest(null, START, END, null);

        Set<ConstraintViolation<StartRoutineRequest>> violations = validator.validate(request);

        assertThat(violations).anySatisfy(violation -> {
            assertThat(violation.getPropertyPath()).hasToString("routineTemplateId");
            assertThat(violation.getMessage()).isEqualTo("루틴 템플릿 ID는 필수입니다.");
        });
    }

    @Test
    @DisplayName("선호시각형식이HH_mm_ss가아니면_검증에실패한다")
    void validate_whenPreferredTimeMalformed_fails() {
        StartRoutineRequest request = new StartRoutineRequest(1L, START, END, "7:00");

        Set<ConstraintViolation<StartRoutineRequest>> violations = validator.validate(request);

        assertThat(violations).anySatisfy(violation -> {
            assertThat(violation.getPropertyPath()).hasToString("preferredTime");
            assertThat(violation.getMessage()).isEqualTo("선호 수행 시각은 HH:mm:ss 형식이어야 합니다.");
        });
    }

    @Test
    @DisplayName("종료일이시작일보다빠르면_검증에실패한다")
    void validate_whenEndBeforeStart_fails() {
        StartRoutineRequest request = new StartRoutineRequest(1L, END, START, null);

        Set<ConstraintViolation<StartRoutineRequest>> violations = validator.validate(request);

        assertThat(violations).anySatisfy(violation ->
                assertThat(violation.getMessage()).isEqualTo("종료일은 시작일보다 빠를 수 없습니다."));
    }

    @Test
    @DisplayName("toCommand는_선호시각문자열을LocalTime으로변환하고userId를담는다")
    void toCommand_convertsPreferredTimeAndCarriesUserId() {
        StartRoutineRequest request = new StartRoutineRequest(1L, START, END, "07:30:00");

        StartRoutineCommand command = request.toCommand(9L);

        assertThat(command.userId()).isEqualTo(9L);
        assertThat(command.routineTemplateId()).isEqualTo(1L);
        assertThat(command.preferredTime()).isEqualTo(LocalTime.of(7, 30));
    }

    @Test
    @DisplayName("toCommand는_선호시각이null이면_null을담는다")
    void toCommand_whenPreferredTimeNull_carriesNull() {
        StartRoutineRequest request = new StartRoutineRequest(1L, START, END, null);

        StartRoutineCommand command = request.toCommand(9L);

        assertThat(command.preferredTime()).isNull();
    }
}
