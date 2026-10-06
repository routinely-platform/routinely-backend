package com.routinely.routine_service.application.routine;

import com.routinely.core.exception.BusinessException;
import com.routinely.routine_service.application.event.RoutineEventPublisher;
import com.routinely.routine_service.application.routine.dto.RoutineResult;
import com.routinely.routine_service.application.routine.dto.StartRoutineCommand;
import com.routinely.routine_service.application.routine.dto.UpdateRoutineCommand;
import com.routinely.routine_service.domain.category.CategoryRepository;
import com.routinely.routine_service.domain.execution.RoutineExecutionRepository;
import com.routinely.routine_service.domain.routine.Routine;
import com.routinely.routine_service.domain.routine.RoutineRepository;
import com.routinely.routine_service.domain.template.RoutineTemplate;
import com.routinely.routine_service.domain.template.RoutineTemplateRepository;
import com.routinely.routine_service.domain.template.ScheduleType;
import java.time.temporal.ChronoUnit;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.routinely.core.exception.ErrorCode.FORBIDDEN;
import static com.routinely.core.exception.ErrorCode.ROUTINE_NOT_FOUND;
import static com.routinely.core.exception.ErrorCode.ROUTINE_TEMPLATE_NOT_FOUND;
import static com.routinely.core.exception.ErrorCode.VALIDATION_FAILED;

/**
 * 루틴 인스턴스(활성 루틴) 라이프사이클 — 개인 루틴 시작 / 목록 조회 / 중단.
 *
 * <p>이 API는 개인 루틴만 다룬다. 챌린지 루틴 인스턴스는 {@code challenge.started} 이벤트 소비로
 * 자동 생성되므로(ADR-0032, 별도 구현) 이 경로로 시작할 수 없고, 중단도 할 수 없다(ADR-0036).
 *
 * <p>선호 수행 시각 설정/수정은 {@code PATCH /api/v1/routines/{routineId}}(#139)가, 실행 기록은
 * routine_executions(#57)가 담당한다.
 */
@RequiredArgsConstructor
@Slf4j
@Service
@Transactional(readOnly = true)
public class RoutineServiceImpl implements RoutineService {

    private final RoutineRepository routineRepository;
    private final RoutineTemplateRepository templateRepository;
    private final RoutineExecutionRepository executionRepository;
    private final CategoryRepository categoryRepository;
    private final RoutineEventPublisher eventPublisher;

    @Transactional
    public RoutineResult start(StartRoutineCommand command) {
        if (command.routineTemplateId() == null) {
            // 템플릿 경로는 템플릿을 만들 때 이미 검증했다. 직접 입력은 여기서 막아야 한다 — DB에 FK가 없다.
            validateCategoryCode(command.definition().getCategoryCode());
        }
        var definition = command.routineTemplateId() == null ? command.definition()
                : getOwnedPersonalTemplateOrThrow(command.routineTemplateId(), command.userId()).getDefinition().copy();

        // ADR-0040 — 정의를 복사한다. 이후 템플릿을 고치거나 지워도 이 루틴은 흔들리지 않는다.
        Routine routine = routineRepository.save(Routine.forPersonal(
                command.routineTemplateId(),
                command.userId(),
                definition,
                command.startedAt(),
                command.endedAt(),
                command.preferredTime()
        ));
        eventPublisher.publishNotificationScheduled(routine);
        return RoutineResult.from(routine);
    }

    public List<RoutineResult> getMyRoutines(Long userId, Boolean isActive, Long challengeId) {
        // ADR-0040 — 루틴이 자기 정의를 갖는다. 템플릿 조회(N+1의 원천)가 사라졌다.
        return routineRepository.findMyRoutines(userId, isActive, challengeId).stream()
                .map(RoutineResult::from)
                .toList();
    }

    /**
     * 개인 루틴 중단 — 물리 삭제 대신 활성 플래그를 내린다. 중단한 루틴은 "오늘 할 일" 파생과 달성률
     * 집계에서 빠지고 완료 이력만 남는다. (ADR-0038)
     *
     * <p><b>챌린지 루틴은 중단할 수 없다.</b> 챌린지 루틴은 모든 참여자에게 고정으로 적용되며(ADR-0036),
     * 인스턴스는 멤버 수만큼 존재하므로 한 사람이 자기 인스턴스를 내려도 챌린지가 끝나지 않는다. 중단을
     * 허용하면 랭킹에서 조용히 이탈하는 통로가 되므로, 챌린지에서 빠지는 수단은 챌린지 탈퇴 하나로 둔다.
     */
    @Transactional
    public void stop(Long routineId, Long userId) {
        Routine routine = findOwnedRoutineByIdOrThrow(routineId, userId);
        if (routine.getChallengeId() != null) {
            throw new BusinessException(FORBIDDEN,
                    "챌린지 루틴은 중단할 수 없습니다. 챌린지에서 탈퇴해 주세요.");
        }

        routine.deactivate();
        eventPublisher.publishNotificationScheduled(routine);
    }

    @Transactional
    public RoutineResult update(Long routineId, Long userId,
            UpdateRoutineCommand command) {
        Routine routine = findOwnedRoutineByIdOrThrow(routineId, userId);
        if (routine.isChallengeRoutine() && command.changesDefinitionOrPeriod()) {
            throw new BusinessException(FORBIDDEN, "챌린지 루틴의 정의와 기간은 수정할 수 없습니다.");
        }
        var definition = routine.getDefinition();
        if (command.scheduleType() != null || command.startedAt() != null) {
            validateHistoryEditable(routineId);
        }
        if (command.title() != null) definition = definition.withTitle(command.title());
        if (command.categoryCode() != null) {
            validateCategoryCode(command.categoryCode());
            definition = definition.withCategoryCode(command.categoryCode());
        }
        if (command.scheduleType() != null) definition = definition.withSchedule(
                ScheduleType.valueOf(command.scheduleType()),
                command.daysOfWeek(), command.targetCount());
        var start = command.startedAt() == null ? routine.getStartedAt() : command.startedAt();
        var end = command.clearEndedAt() ? null : command.endedAt() == null ? routine.getEndedAt() : command.endedAt();
        if (command.startedAt() != null || command.endedAt() != null || command.clearEndedAt()) {
            if (end != null && (end.isBefore(start) || ChronoUnit.DAYS.between(start, end) >= 366)) {
                throw new BusinessException(VALIDATION_FAILED, "종료일은 시작일 이후, 시작일 포함 366일 이내여야 합니다.");
            }
            if (end != null) executionRepository.findTopByRoutineIdOrderByScheduledDateDesc(routineId)
                    .filter(row -> row.getScheduledDate().isAfter(end))
                    .ifPresent(row -> { throw new BusinessException(VALIDATION_FAILED, "종료일은 마지막 완료일보다 빠를 수 없습니다."); });
        }
        routine.changeDefinition(definition);
        routine.changePeriod(start, end);
        routine.changePreferences(command.clearPreferredTime() ? null : command.preferredTime() == null
                ? routine.getPreferredTime() : command.preferredTime(),
                command.clearPreferredDays() ? null : command.preferredDays() == null
                ? routine.getPreferredDays() : command.preferredDays());
        eventPublisher.publishNotificationScheduled(routine);
        return RoutineResult.from(routine);
    }

    /**
     * 주기·시작일은 완료 이력이 하나도 없을 때만 바꿀 수 있다.
     *
     * <p>ADR-0040 — 지난 완료의 달성률은 그때의 주기·시작일로 판정한다. 이력이 쌓인 뒤 주기를 바꾸면
     * 이미 인증한 날이 통계에서 MISSED로 뒤집힌다. 루틴이 자기 정의를 복사해 갖는 것과 같은 이유로,
     * 정의를 바꾸고 싶으면 이 루틴을 중단하고 새 루틴을 시작한다.
     */
    private void validateHistoryEditable(Long routineId) {
        if (executionRepository.existsByRoutineId(routineId)) {
            throw new BusinessException(VALIDATION_FAILED,
                    "완료 기록이 있는 루틴의 주기와 시작일은 수정할 수 없습니다. 루틴을 중단하고 새로 시작해 주세요.");
        }
    }

    /** 활성 카테고리 코드인지 — {@code RoutineTemplateService}와 같은 규칙이다. routines.category_code에는 FK가 없다. */
    private void validateCategoryCode(String categoryCode) {
        if (!categoryRepository.existsByCodeAndIsActiveTrue(categoryCode)) {
            throw new BusinessException(VALIDATION_FAILED, "유효하지 않은 카테고리 코드입니다.");
        }
    }

    private Routine findOwnedRoutineByIdOrThrow(Long routineId, Long userId) {
        return routineRepository.findLockedByIdAndUserId(routineId, userId)
                .orElseThrow(() -> new BusinessException(ROUTINE_NOT_FOUND));
    }

    private RoutineTemplate getOwnedPersonalTemplateOrThrow(Long templateId, Long userId) {
        RoutineTemplate template = templateRepository.findByIdAndIsDeletedFalse(templateId)
                .orElseThrow(() -> new BusinessException(ROUTINE_TEMPLATE_NOT_FOUND));

        if (!template.getUserId().equals(userId)) {
            throw new BusinessException(FORBIDDEN, "본인의 루틴 템플릿으로만 루틴을 시작할 수 있습니다.");
        }
        if (template.isChallengeLinked()) {
            throw new BusinessException(FORBIDDEN, "챌린지 루틴은 챌린지 시작 시 자동으로 생성됩니다.");
        }
        return template;
    }

}
