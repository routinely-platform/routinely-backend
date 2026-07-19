package com.routinely.routine_service.presentation.rest.routine.dto.request;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalTime;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("UpdateRoutinePreferredTimeRequest")
class UpdateRoutinePreferredTimeRequestTest {

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
    @DisplayName("HH_mm_ss형식이면_검증에성공한다")
    void validate_whenWellFormed_succeeds() {
        UpdateRoutinePreferredTimeRequest request = new UpdateRoutinePreferredTimeRequest("07:00:00");

        Set<ConstraintViolation<UpdateRoutinePreferredTimeRequest>> violations = validator.validate(request);

        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("null이면_설정해제로간주되어_검증에성공한다")
    void validate_whenNull_succeeds() {
        UpdateRoutinePreferredTimeRequest request = new UpdateRoutinePreferredTimeRequest(null);

        Set<ConstraintViolation<UpdateRoutinePreferredTimeRequest>> violations = validator.validate(request);

        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("형식이HH_mm_ss가아니면_검증에실패한다")
    void validate_whenMalformed_fails() {
        UpdateRoutinePreferredTimeRequest request = new UpdateRoutinePreferredTimeRequest("7:00");

        Set<ConstraintViolation<UpdateRoutinePreferredTimeRequest>> violations = validator.validate(request);

        assertThat(violations).anySatisfy(violation -> {
            assertThat(violation.getPropertyPath()).hasToString("preferredTime");
            assertThat(violation.getMessage()).isEqualTo("선호 수행 시각은 HH:mm:ss 형식이어야 합니다.");
        });
    }

    @Test
    @DisplayName("toPreferredTime은_문자열을LocalTime으로변환한다")
    void toPreferredTime_parsesToLocalTime() {
        UpdateRoutinePreferredTimeRequest request = new UpdateRoutinePreferredTimeRequest("07:30:00");

        assertThat(request.toPreferredTime()).isEqualTo(LocalTime.of(7, 30));
    }

    @Test
    @DisplayName("toPreferredTime은_null이면_null을반환한다")
    void toPreferredTime_whenNull_returnsNull() {
        UpdateRoutinePreferredTimeRequest request = new UpdateRoutinePreferredTimeRequest(null);

        assertThat(request.toPreferredTime()).isNull();
    }
}
