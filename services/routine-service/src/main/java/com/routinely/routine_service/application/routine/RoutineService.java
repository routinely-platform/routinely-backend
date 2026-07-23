package com.routinely.routine_service.application.routine;

import com.routinely.core.exception.BusinessException;
import com.routinely.routine_service.application.routine.dto.PreferencesResult;
import com.routinely.routine_service.application.routine.dto.RoutineResult;
import com.routinely.routine_service.application.routine.dto.StartRoutineCommand;
import com.routinely.routine_service.domain.routine.Routine;
import com.routinely.routine_service.domain.routine.RoutineRepository;
import com.routinely.routine_service.domain.template.RoutineTemplate;
import com.routinely.routine_service.domain.template.RoutineTemplateRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.routinely.core.exception.ErrorCode.FORBIDDEN;
import static com.routinely.core.exception.ErrorCode.ROUTINE_NOT_FOUND;
import static com.routinely.core.exception.ErrorCode.ROUTINE_TEMPLATE_NOT_FOUND;
import static com.routinely.core.exception.ErrorCode.VALIDATION_FAILED;

/**
 * 루틴 인스턴스(활성 루틴) 라이프사이클 — 개인 루틴 시작 / 목록 조회 / 중단.
 *
 * <p>이 API는 개인 루틴만 다룬다. 챌린지 루틴 인스턴스는 {@code challenge.started} 이벤트 소비로
 * 자동 생성되므로(ADR-0032, 별도 구현) 이 경로로 시작할 수 없다.
 *
 * <p>선호 수행 시각 설정/수정은 {@code PATCH /api/v1/routines/{routineId}}(#139)가, 실행 기록은
 * routine_executions(#57)가 담당한다.
 */
@Service
@Transactional(readOnly = true)
public class RoutineService {

    private final RoutineRepository routineRepository;
    private final RoutineTemplateRepository templateRepository;

    public RoutineService(RoutineRepository routineRepository,
                          RoutineTemplateRepository templateRepository) {
        this.routineRepository = routineRepository;
        this.templateRepository = templateRepository;
    }

    @Transactional
    public RoutineResult start(StartRoutineCommand command) {
        RoutineTemplate template = getOwnedPersonalTemplateOrThrow(
                command.routineTemplateId(), command.userId());
        validateDateRange(command);

        Routine routine = routineRepository.save(Routine.forPersonal(
                template.getId(),
                command.userId(),
                command.startedAt(),
                command.endedAt(),
                command.preferredTime()
        ));
        return RoutineResult.from(routine, template.getTitle());
    }

    public List<RoutineResult> getMyRoutines(Long userId, Boolean isActive, Long challengeId) {
        List<Routine> routines = routineRepository.findMyRoutines(userId, isActive, challengeId);
        Map<Long, String> titleByTemplateId = resolveTemplateTitles(routines);

        return routines.stream()
                .map(routine -> RoutineResult.from(
                        routine, titleByTemplateId.get(routine.getRoutineTemplateId())))
                .toList();
    }

    @Transactional
    public void stop(Long routineId, Long userId) {
        Routine routine = routineRepository.findByIdAndUserId(routineId, userId)
                .orElseThrow(() -> new BusinessException(ROUTINE_NOT_FOUND));

        routine.deactivate();
    }

    /**
     * 선호 설정(선호 시각 + 선호 요일) 설정/변경 — 본인 소유 루틴만 대상이며, 없거나 소유자가 아니면
     * ROUTINE_NOT_FOUND(404). 각 값이 null이면 해당 설정을 해제한다. 요일은 완료를 제약하지 않는 soft
     * 선호다. (ADR-0035, ADR-0039)
     */
    @Transactional
    public PreferencesResult updatePreferences(Long routineId, Long userId,
                                               LocalTime preferredTime, Short preferredDays) {
        validatePreferredDays(preferredDays);

        Routine routine = routineRepository.findByIdAndUserId(routineId, userId)
                .orElseThrow(() -> new BusinessException(ROUTINE_NOT_FOUND));

        routine.changePreferences(preferredTime, preferredDays);
        return PreferencesResult.from(routine);
    }

    /**
     * 선호 요일 비트마스크 정합성 검증({@code ck_routines_preferred_days} 미러링). null은 선호 요일 해제로
     * 허용하고, 값이 있으면 월~일 7비트(1~127)만 유효하다. 요청 DTO에서 1차 검증되지만 직접/내부 호출을
     * 방어한다(템플릿의 {@code validateSchedule}와 동일한 방어 레벨).
     */
    private void validatePreferredDays(Short preferredDays) {
        if (preferredDays != null && (preferredDays < 1 || preferredDays > 127)) {
            throw new BusinessException(VALIDATION_FAILED,
                    "선호 요일은 월~일(비트마스크 1~127) 범위여야 합니다.");
        }
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

    private void validateDateRange(StartRoutineCommand command) {
        if (command.endedAt().isBefore(command.startedAt())) {
            throw new BusinessException(VALIDATION_FAILED, "종료일은 시작일보다 빠를 수 없습니다.");
        }
    }

    /**
     * 인스턴스가 참조하는 템플릿 이름을 한 번의 조회로 모은다. 템플릿이 소프트 삭제됐어도 인스턴스는
     * 유지되므로(routines.routine_template_id 주석 참고) is_deleted 필터 없이 조회한다.
     */
    private Map<Long, String> resolveTemplateTitles(List<Routine> routines) {
        List<Long> templateIds = routines.stream()
                .map(Routine::getRoutineTemplateId)
                .distinct()
                .toList();

        return templateRepository.findAllById(templateIds).stream()
                .collect(Collectors.toMap(RoutineTemplate::getId, RoutineTemplate::getTitle));
    }
}
