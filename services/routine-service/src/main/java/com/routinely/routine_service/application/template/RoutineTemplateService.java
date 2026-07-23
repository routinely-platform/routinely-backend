package com.routinely.routine_service.application.template;

import com.routinely.core.exception.BusinessException;
import com.routinely.routine_service.application.template.dto.CreateRoutineTemplateCommand;
import com.routinely.routine_service.application.template.dto.RoutineTemplateResult;
import com.routinely.routine_service.application.template.dto.UpdateRoutineTemplateCommand;
import com.routinely.routine_service.domain.category.CategoryRepository;
import com.routinely.routine_service.domain.template.RoutineTemplate;
import com.routinely.routine_service.domain.template.RoutineTemplateRepository;
import com.routinely.routine_service.domain.template.ScheduleType;
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
 * <p>스케줄 유형별 필드 정합성(schedule_type / days_of_week / target_count)은 routine_templates의
 * {@code ck_rt_schedule} CHECK 제약을 앱 계층에서 미러링해 저장 전에 검증한다. (ADR-0039)
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
        validateSchedule(command.scheduleType(), command.daysOfWeek(), command.targetCount());

        RoutineTemplate template = templateRepository.save(RoutineTemplate.forPersonal(
                command.userId(),
                command.title(),
                command.categoryCode(),
                command.scheduleType(),
                command.daysOfWeek(),
                command.targetCount()
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
        if (command.hasScheduleChange()) {
            validateSchedule(command.scheduleType(), command.daysOfWeek(), command.targetCount());
            template.changeSchedule(command.scheduleType(), command.daysOfWeek(), command.targetCount());
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

    /**
     * 스케줄 유형별 필드 정합성을 검증한다({@code ck_rt_schedule} 미러링). 요청 DTO에서 1차 검증되지만
     * 도메인 저장 직전 방어적으로 재검증한다.
     */
    private void validateSchedule(ScheduleType scheduleType, Short daysOfWeek, Integer targetCount) {
        // ck_rt_schedule와 완전 동치가 되도록 daysOfWeek=0(NOT NULL)도 "제공됨"으로 본다.
        // 0은 DB에서 NULL이 아니어서 DAILY/빈도 유형 CHECK에 위배되고, SPECIFIC_DAYS에서도 1~127 범위 밖이다.
        boolean hasDays = daysOfWeek != null;
        boolean hasCount = targetCount != null;

        if (scheduleType.requiresDaysOfWeek()) {
            if (!hasDays || hasCount) {
                throw new BusinessException(VALIDATION_FAILED,
                        "SPECIFIC_DAYS는 요일 지정이 필요하며 목표 횟수를 가질 수 없습니다.");
            }
            // ck_rt_schedule의 days_of_week BETWEEN 1 AND 127 미러링 — API DTO 외 직접/내부 호출 방어.
            if (daysOfWeek < 1 || daysOfWeek > 127) {
                throw new BusinessException(VALIDATION_FAILED,
                        "요일 지정은 월~일(비트마스크 1~127) 범위여야 합니다.");
            }
        } else if (scheduleType.requiresTargetCount()) {
            if (!hasCount || targetCount < 1 || hasDays) {
                throw new BusinessException(VALIDATION_FAILED,
                        "WEEKLY_COUNT/MONTHLY_COUNT는 1 이상의 목표 횟수가 필요하며 요일을 가질 수 없습니다.");
            }
        } else if (hasDays || hasCount) {
            throw new BusinessException(VALIDATION_FAILED, "DAILY는 요일·목표 횟수를 가질 수 없습니다.");
        }
    }
}
