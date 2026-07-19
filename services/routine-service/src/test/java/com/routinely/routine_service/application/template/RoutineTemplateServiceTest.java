package com.routinely.routine_service.application.template;

import com.routinely.core.exception.BusinessException;
import com.routinely.core.exception.ErrorCode;
import com.routinely.routine_service.application.template.dto.CreateRoutineTemplateCommand;
import com.routinely.routine_service.application.template.dto.RoutineTemplateResult;
import com.routinely.routine_service.application.template.dto.UpdateRoutineTemplateCommand;
import com.routinely.routine_service.domain.category.CategoryRepository;
import com.routinely.routine_service.domain.template.RepeatType;
import com.routinely.routine_service.domain.template.RoutineTemplate;
import com.routinely.routine_service.domain.template.RoutineTemplateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("RoutineTemplateService")
class RoutineTemplateServiceTest {

    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-07-16T00:00:00Z"), ZoneId.of("Asia/Seoul"));

    private static final Long OWNER_ID = 1L;
    private static final Long OTHER_USER_ID = 2L;
    private static final Long TEMPLATE_ID = 10L;

    private RoutineTemplateRepository templateRepository;
    private CategoryRepository categoryRepository;
    private RoutineTemplateService service;

    @BeforeEach
    void setUp() {
        templateRepository = mock(RoutineTemplateRepository.class);
        categoryRepository = mock(CategoryRepository.class);
        service = new RoutineTemplateService(templateRepository, categoryRepository, FIXED_CLOCK);
    }

    private RoutineTemplate personalTemplate() {
        RoutineTemplate template = RoutineTemplate.forPersonal(
                OWNER_ID, "아침 러닝 30분", "EXERCISE", RepeatType.DAILY, null);
        ReflectionTestUtils.setField(template, "id", TEMPLATE_ID);
        return template;
    }

    private RoutineTemplate challengeTemplate() {
        RoutineTemplate template = RoutineTemplate.forChallenge(
                OWNER_ID, 42L, "아침 러닝 30분", "EXERCISE", RepeatType.WEEKLY_N, 3);
        ReflectionTestUtils.setField(template, "id", TEMPLATE_ID);
        return template;
    }

    @Nested
    @DisplayName("create")
    class Create {

        @Test
        @DisplayName("유효한커맨드면_개인템플릿을저장하고결과를반환한다")
        void create_whenValid_savesPersonalTemplate() {
            when(categoryRepository.existsByCodeAndIsActiveTrue("EXERCISE")).thenReturn(true);
            when(templateRepository.save(any(RoutineTemplate.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            RoutineTemplateResult result = service.create(new CreateRoutineTemplateCommand(
                    OWNER_ID, "아침 러닝 30분", "EXERCISE", RepeatType.WEEKLY_N, 3));

            ArgumentCaptor<RoutineTemplate> captor = ArgumentCaptor.forClass(RoutineTemplate.class);
            verify(templateRepository).save(captor.capture());
            RoutineTemplate saved = captor.getValue();
            assertThat(saved.getUserId()).isEqualTo(OWNER_ID);
            assertThat(saved.getChallengeId()).isNull();
            assertThat(saved.isDeleted()).isFalse();

            assertThat(result.title()).isEqualTo("아침 러닝 30분");
            assertThat(result.categoryCode()).isEqualTo("EXERCISE");
            assertThat(result.repeatType()).isEqualTo(RepeatType.WEEKLY_N);
            assertThat(result.repeatValue()).isEqualTo(3);
            assertThat(result.challengeId()).isNull();
        }

        @Test
        @DisplayName("유효하지않은카테고리코드면_검증예외를던진다")
        void create_whenInvalidCategoryCode_throwsValidationFailed() {
            when(categoryRepository.existsByCodeAndIsActiveTrue("UNKNOWN")).thenReturn(false);

            assertThatThrownBy(() -> service.create(new CreateRoutineTemplateCommand(
                    OWNER_ID, "아침 러닝 30분", "UNKNOWN", RepeatType.DAILY, null)))
                    .isInstanceOfSatisfying(BusinessException.class, exception -> {
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                        assertThat(exception.getMessage()).isEqualTo("유효하지 않은 카테고리 코드입니다.");
                    });

            verify(templateRepository, never()).save(any());
        }

        @Test
        @DisplayName("반복횟수필수유형인데_반복횟수가없으면_검증예외를던진다")
        void create_whenRepeatValueMissingForNType_throwsValidationFailed() {
            when(categoryRepository.existsByCodeAndIsActiveTrue("EXERCISE")).thenReturn(true);

            assertThatThrownBy(() -> service.create(new CreateRoutineTemplateCommand(
                    OWNER_ID, "아침 러닝 30분", "EXERCISE", RepeatType.DAILY_N, null)))
                    .isInstanceOfSatisfying(BusinessException.class, exception ->
                            assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));

            verify(templateRepository, never()).save(any());
        }

        @Test
        @DisplayName("반복횟수없는유형인데_반복횟수가있으면_검증예외를던진다")
        void create_whenRepeatValueGivenForDaily_throwsValidationFailed() {
            when(categoryRepository.existsByCodeAndIsActiveTrue("EXERCISE")).thenReturn(true);

            assertThatThrownBy(() -> service.create(new CreateRoutineTemplateCommand(
                    OWNER_ID, "아침 러닝 30분", "EXERCISE", RepeatType.DAILY, 3)))
                    .isInstanceOfSatisfying(BusinessException.class, exception ->
                            assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));

            verify(templateRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("getMyTemplates")
    class GetMyTemplates {

        @Test
        @DisplayName("카테고리필터가없으면_전체개인템플릿을조회한다")
        void getMyTemplates_withoutFilter_queriesAll() {
            when(templateRepository.findAllByUserIdAndChallengeIdIsNullAndIsDeletedFalseOrderByIdDesc(OWNER_ID))
                    .thenReturn(List.of(personalTemplate()));

            List<RoutineTemplateResult> results = service.getMyTemplates(OWNER_ID, null);

            assertThat(results).hasSize(1);
            assertThat(results.getFirst().templateId()).isEqualTo(TEMPLATE_ID);
            verify(templateRepository, never())
                    .findAllByUserIdAndChallengeIdIsNullAndCategoryCodeAndIsDeletedFalseOrderByIdDesc(
                            any(), anyString());
        }

        @Test
        @DisplayName("카테고리필터가있으면_해당카테고리만조회한다")
        void getMyTemplates_withFilter_queriesByCategory() {
            when(templateRepository
                    .findAllByUserIdAndChallengeIdIsNullAndCategoryCodeAndIsDeletedFalseOrderByIdDesc(
                            OWNER_ID, "EXERCISE"))
                    .thenReturn(List.of(personalTemplate()));

            List<RoutineTemplateResult> results = service.getMyTemplates(OWNER_ID, "EXERCISE");

            assertThat(results).hasSize(1);
            verify(templateRepository, never())
                    .findAllByUserIdAndChallengeIdIsNullAndIsDeletedFalseOrderByIdDesc(any());
        }
    }

    @Nested
    @DisplayName("getTemplate")
    class GetTemplate {

        @Test
        @DisplayName("소유자가조회하면_상세를반환한다")
        void getTemplate_whenOwner_returnsDetail() {
            when(templateRepository.findByIdAndIsDeletedFalse(TEMPLATE_ID))
                    .thenReturn(Optional.of(personalTemplate()));

            RoutineTemplateResult result = service.getTemplate(TEMPLATE_ID, OWNER_ID);

            assertThat(result.templateId()).isEqualTo(TEMPLATE_ID);
            assertThat(result.title()).isEqualTo("아침 러닝 30분");
        }

        @Test
        @DisplayName("없거나삭제된템플릿이면_NOT_FOUND예외를던진다")
        void getTemplate_whenNotFound_throwsNotFound() {
            when(templateRepository.findByIdAndIsDeletedFalse(TEMPLATE_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getTemplate(TEMPLATE_ID, OWNER_ID))
                    .isInstanceOfSatisfying(BusinessException.class, exception ->
                            assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.ROUTINE_TEMPLATE_NOT_FOUND));
        }

        @Test
        @DisplayName("소유자가아니면_FORBIDDEN예외를던진다")
        void getTemplate_whenNotOwner_throwsForbidden() {
            when(templateRepository.findByIdAndIsDeletedFalse(TEMPLATE_ID))
                    .thenReturn(Optional.of(personalTemplate()));

            assertThatThrownBy(() -> service.getTemplate(TEMPLATE_ID, OTHER_USER_ID))
                    .isInstanceOfSatisfying(BusinessException.class, exception -> {
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN);
                        assertThat(exception.getMessage()).isEqualTo("본인의 루틴 템플릿만 접근할 수 있습니다.");
                    });
        }

        @Test
        @DisplayName("챌린지연결템플릿이면_소유자여도_FORBIDDEN예외를던진다")
        void getTemplate_whenChallengeLinked_throwsForbidden() {
            when(templateRepository.findByIdAndIsDeletedFalse(TEMPLATE_ID))
                    .thenReturn(Optional.of(challengeTemplate()));

            assertThatThrownBy(() -> service.getTemplate(TEMPLATE_ID, OWNER_ID))
                    .isInstanceOfSatisfying(BusinessException.class, exception -> {
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN);
                        assertThat(exception.getMessage())
                                .isEqualTo("챌린지 루틴 템플릿은 개인 루틴 템플릿 API로 접근할 수 없습니다.");
                    });
        }
    }

    @Nested
    @DisplayName("update")
    class Update {

        @Test
        @DisplayName("제목만수정하면_나머지필드는유지된다")
        void update_whenTitleOnly_keepsOtherFields() {
            RoutineTemplate template = personalTemplate();
            when(templateRepository.findByIdAndIsDeletedFalse(TEMPLATE_ID)).thenReturn(Optional.of(template));

            RoutineTemplateResult result = service.update(new UpdateRoutineTemplateCommand(
                    TEMPLATE_ID, OWNER_ID, "저녁 러닝 30분", null, null, null));

            assertThat(result.title()).isEqualTo("저녁 러닝 30분");
            assertThat(result.categoryCode()).isEqualTo("EXERCISE");
            assertThat(result.repeatType()).isEqualTo(RepeatType.DAILY);
            assertThat(result.repeatValue()).isNull();
            verify(categoryRepository, never()).existsByCodeAndIsActiveTrue(anyString());
        }

        @Test
        @DisplayName("카테고리를수정하면_카테고리코드를검증한다")
        void update_whenCategoryChanged_validatesCategoryCode() {
            RoutineTemplate template = personalTemplate();
            when(templateRepository.findByIdAndIsDeletedFalse(TEMPLATE_ID)).thenReturn(Optional.of(template));
            when(categoryRepository.existsByCodeAndIsActiveTrue("READING")).thenReturn(true);

            RoutineTemplateResult result = service.update(new UpdateRoutineTemplateCommand(
                    TEMPLATE_ID, OWNER_ID, null, "READING", null, null));

            assertThat(result.categoryCode()).isEqualTo("READING");
            verify(categoryRepository).existsByCodeAndIsActiveTrue("READING");
        }

        @Test
        @DisplayName("유효하지않은카테고리로수정하면_검증예외를던지고변경하지않는다")
        void update_whenInvalidCategory_throwsValidationFailed() {
            RoutineTemplate template = personalTemplate();
            when(templateRepository.findByIdAndIsDeletedFalse(TEMPLATE_ID)).thenReturn(Optional.of(template));
            when(categoryRepository.existsByCodeAndIsActiveTrue("UNKNOWN")).thenReturn(false);

            assertThatThrownBy(() -> service.update(new UpdateRoutineTemplateCommand(
                    TEMPLATE_ID, OWNER_ID, null, "UNKNOWN", null, null)))
                    .isInstanceOfSatisfying(BusinessException.class, exception ->
                            assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));

            assertThat(template.getCategoryCode()).isEqualTo("EXERCISE");
        }

        @Test
        @DisplayName("반복설정을쌍으로수정하면_함께변경된다")
        void update_whenRepeatPairChanged_updatesBoth() {
            RoutineTemplate template = personalTemplate();
            when(templateRepository.findByIdAndIsDeletedFalse(TEMPLATE_ID)).thenReturn(Optional.of(template));

            RoutineTemplateResult result = service.update(new UpdateRoutineTemplateCommand(
                    TEMPLATE_ID, OWNER_ID, null, null, RepeatType.WEEKLY_N, 3));

            assertThat(result.repeatType()).isEqualTo(RepeatType.WEEKLY_N);
            assertThat(result.repeatValue()).isEqualTo(3);
        }

        @Test
        @DisplayName("반복횟수필수유형으로_반복횟수없이수정하면_검증예외를던진다")
        void update_whenRepeatValueMissingForNType_throwsValidationFailed() {
            RoutineTemplate template = personalTemplate();
            when(templateRepository.findByIdAndIsDeletedFalse(TEMPLATE_ID)).thenReturn(Optional.of(template));

            assertThatThrownBy(() -> service.update(new UpdateRoutineTemplateCommand(
                    TEMPLATE_ID, OWNER_ID, null, null, RepeatType.MONTHLY_N, null)))
                    .isInstanceOfSatisfying(BusinessException.class, exception ->
                            assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));

            assertThat(template.getRepeatType()).isEqualTo(RepeatType.DAILY);
        }

        @Test
        @DisplayName("소유자가아니면_FORBIDDEN예외를던진다")
        void update_whenNotOwner_throwsForbidden() {
            when(templateRepository.findByIdAndIsDeletedFalse(TEMPLATE_ID))
                    .thenReturn(Optional.of(personalTemplate()));

            assertThatThrownBy(() -> service.update(new UpdateRoutineTemplateCommand(
                    TEMPLATE_ID, OTHER_USER_ID, "저녁 러닝 30분", null, null, null)))
                    .isInstanceOfSatisfying(BusinessException.class, exception ->
                            assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        }

        @Test
        @DisplayName("챌린지연결템플릿이면_FORBIDDEN예외를던진다")
        void update_whenChallengeLinked_throwsForbidden() {
            when(templateRepository.findByIdAndIsDeletedFalse(TEMPLATE_ID))
                    .thenReturn(Optional.of(challengeTemplate()));

            assertThatThrownBy(() -> service.update(new UpdateRoutineTemplateCommand(
                    TEMPLATE_ID, OWNER_ID, "저녁 러닝 30분", null, null, null)))
                    .isInstanceOfSatisfying(BusinessException.class, exception -> {
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN);
                        assertThat(exception.getMessage())
                                .isEqualTo("챌린지 루틴 템플릿은 개인 루틴 템플릿 API로 접근할 수 없습니다.");
                    });
        }
    }

    @Nested
    @DisplayName("delete")
    class Delete {

        @Test
        @DisplayName("소유자가삭제하면_소프트삭제되고삭제시각이기록된다")
        void delete_whenOwner_softDeletes() {
            RoutineTemplate template = personalTemplate();
            when(templateRepository.findByIdAndIsDeletedFalse(TEMPLATE_ID)).thenReturn(Optional.of(template));

            service.delete(TEMPLATE_ID, OWNER_ID);

            assertThat(template.isDeleted()).isTrue();
            assertThat(template.getDeletedAt()).isEqualTo(LocalDateTime.now(FIXED_CLOCK));
            verify(templateRepository, never()).delete(any());
        }

        @Test
        @DisplayName("없거나이미삭제된템플릿이면_NOT_FOUND예외를던진다")
        void delete_whenNotFound_throwsNotFound() {
            when(templateRepository.findByIdAndIsDeletedFalse(TEMPLATE_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.delete(TEMPLATE_ID, OWNER_ID))
                    .isInstanceOfSatisfying(BusinessException.class, exception ->
                            assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.ROUTINE_TEMPLATE_NOT_FOUND));
        }

        @Test
        @DisplayName("소유자가아니면_FORBIDDEN예외를던지고삭제하지않는다")
        void delete_whenNotOwner_throwsForbidden() {
            RoutineTemplate template = personalTemplate();
            when(templateRepository.findByIdAndIsDeletedFalse(TEMPLATE_ID)).thenReturn(Optional.of(template));

            assertThatThrownBy(() -> service.delete(TEMPLATE_ID, OTHER_USER_ID))
                    .isInstanceOfSatisfying(BusinessException.class, exception ->
                            assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));

            assertThat(template.isDeleted()).isFalse();
        }

        @Test
        @DisplayName("챌린지연결템플릿이면_FORBIDDEN예외를던지고삭제하지않는다")
        void delete_whenChallengeLinked_throwsForbidden() {
            RoutineTemplate template = challengeTemplate();
            when(templateRepository.findByIdAndIsDeletedFalse(TEMPLATE_ID)).thenReturn(Optional.of(template));

            assertThatThrownBy(() -> service.delete(TEMPLATE_ID, OWNER_ID))
                    .isInstanceOfSatisfying(BusinessException.class, exception ->
                            assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));

            assertThat(template.isDeleted()).isFalse();
        }
    }
}
