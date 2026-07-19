package com.routinely.routine_service.application.template;

import com.routinely.core.exception.BusinessException;
import com.routinely.routine_service.application.template.dto.CreateRoutineTemplateCommand;
import com.routinely.routine_service.application.template.dto.RoutineTemplateResult;
import com.routinely.routine_service.application.template.dto.UpdateRoutineTemplateCommand;
import com.routinely.routine_service.domain.category.CategoryRepository;
import com.routinely.routine_service.domain.template.RepeatType;
import com.routinely.routine_service.domain.template.RoutineTemplate;
import com.routinely.routine_service.domain.template.RoutineTemplateRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

import static com.routinely.core.exception.ErrorCode.FORBIDDEN;
import static com.routinely.core.exception.ErrorCode.ROUTINE_TEMPLATE_NOT_FOUND;
import static com.routinely.core.exception.ErrorCode.VALIDATION_FAILED;

/**
 * 개인 루틴 템플릿 CRUD.
 *
 * <p>챌린지 연결 템플릿(challenge_id NOT NULL)은 challenge.created 이벤트 소비로만 생성되며(#133),
 * 이 API로는 조회/수정/삭제 모두 불가하다. 챌린지 루틴은 챌린지/피드 맥락에서 노출한다.
 *
 * <p>repeat_type/repeat_value 정합성은 routine_templates의 {@code ck_rt_repeat_value}
 * CHECK 제약을 앱 계층에서 미러링해 저장 전에 검증한다.
 */
@Service
@Transactional(readOnly = true)
public class RoutineTemplateService {

    private final RoutineTemplateRepository templateRepository;
    private final CategoryRepository categoryRepository;
    private final Clock clock;

    public RoutineTemplateService(RoutineTemplateRepository templateRepository,
                                  CategoryRepository categoryRepository,
                                  Clock clock) {
        this.templateRepository = templateRepository;
        this.categoryRepository = categoryRepository;
        this.clock = clock;
    }

    @Transactional
    public RoutineTemplateResult create(CreateRoutineTemplateCommand command) {
        validateCategoryCode(command.categoryCode());
        validateRepeatPair(command.repeatType(), command.repeatValue());

        RoutineTemplate template = templateRepository.save(RoutineTemplate.forPersonal(
                command.userId(),
                command.title(),
                command.categoryCode(),
                command.repeatType(),
                command.repeatValue()
        ));
        return RoutineTemplateResult.from(template);
    }

    public List<RoutineTemplateResult> getMyTemplates(Long userId, String categoryCode) {
        List<RoutineTemplate> templates = (categoryCode == null || categoryCode.isBlank())
                ? templateRepository.findAllByUserIdAndChallengeIdIsNullAndIsDeletedFalseOrderByIdDesc(userId)
                : templateRepository.findAllByUserIdAndChallengeIdIsNullAndCategoryCodeAndIsDeletedFalseOrderByIdDesc(
                        userId, categoryCode);

        return templates.stream()
                .map(RoutineTemplateResult::from)
                .toList();
    }

    public RoutineTemplateResult getTemplate(Long templateId, Long userId) {
        RoutineTemplate template = getOwnedTemplateOrThrow(templateId, userId);
        validateNotChallengeLinked(template);

        return RoutineTemplateResult.from(template);
    }

    @Transactional
    public RoutineTemplateResult update(UpdateRoutineTemplateCommand command) {
        RoutineTemplate template = getOwnedTemplateOrThrow(command.templateId(), command.userId());
        validateNotChallengeLinked(template);

        if (command.title() != null) {
            template.changeTitle(command.title());
        }
        if (command.categoryCode() != null) {
            validateCategoryCode(command.categoryCode());
            template.changeCategoryCode(command.categoryCode());
        }
        if (command.repeatType() != null) {
            validateRepeatPair(command.repeatType(), command.repeatValue());
            template.changeRepeat(command.repeatType(), command.repeatValue());
        }
        return RoutineTemplateResult.from(template);
    }

    @Transactional
    public void delete(Long templateId, Long userId) {
        RoutineTemplate template = getOwnedTemplateOrThrow(templateId, userId);
        validateNotChallengeLinked(template);

        template.softDelete(LocalDateTime.now(clock));
    }

    private RoutineTemplate getOwnedTemplateOrThrow(Long templateId, Long userId) {
        RoutineTemplate template = templateRepository.findByIdAndIsDeletedFalse(templateId)
                .orElseThrow(() -> new BusinessException(ROUTINE_TEMPLATE_NOT_FOUND));

        if (!template.getUserId().equals(userId)) {
            throw new BusinessException(FORBIDDEN, "본인의 루틴 템플릿만 접근할 수 있습니다.");
        }
        return template;
    }

    private void validateNotChallengeLinked(RoutineTemplate template) {
        if (template.isChallengeLinked()) {
            throw new BusinessException(FORBIDDEN, "챌린지 루틴 템플릿은 개인 루틴 템플릿 API로 접근할 수 없습니다.");
        }
    }

    private void validateCategoryCode(String categoryCode) {
        if (!categoryRepository.existsByCodeAndIsActiveTrue(categoryCode)) {
            throw new BusinessException(VALIDATION_FAILED, "유효하지 않은 카테고리 코드입니다.");
        }
    }

    private void validateRepeatPair(RepeatType repeatType, Integer repeatValue) {
        if (repeatType.requiresValue()) {
            if (repeatValue == null || repeatValue < 1) {
                throw new BusinessException(VALIDATION_FAILED,
                        "반복 횟수는 DAILY_N/WEEKLY_N/MONTHLY_N에서 필수이며 1 이상이어야 합니다.");
            }
        } else if (repeatValue != null) {
            throw new BusinessException(VALIDATION_FAILED,
                    "반복 횟수는 DAILY_N/WEEKLY_N/MONTHLY_N에서만 지정할 수 있습니다.");
        }
    }
}
