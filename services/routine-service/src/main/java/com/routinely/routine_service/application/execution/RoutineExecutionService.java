package com.routinely.routine_service.application.execution;

import com.routinely.core.exception.BusinessException;
import com.routinely.core.exception.ErrorCode;
import com.routinely.routine_service.application.execution.dto.CompleteExecutionCommand;
import com.routinely.routine_service.application.execution.dto.ExecutionCompleteResult;
import com.routinely.routine_service.application.execution.dto.ExecutionResult;
import com.routinely.routine_service.domain.execution.ExecutionStatus;
import com.routinely.routine_service.domain.execution.RoutineExecution;
import com.routinely.routine_service.domain.execution.RoutineExecutionRepository;
import com.routinely.routine_service.domain.routine.Routine;
import com.routinely.routine_service.domain.routine.RoutineRepository;
import com.routinely.routine_service.domain.template.RoutineTemplate;
import com.routinely.routine_service.domain.template.RoutineTemplateRepository;
import com.routinely.storage.FileStorage;
import com.routinely.storage.FileUploadCommand;
import com.routinely.storage.StoredFile;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 루틴 실행 기록 — 완료 처리 / 완료 취소 / 조회.
 *
 * <p><b>sparse 저장</b>(ADR-0038): 사용자가 명시적으로 남긴 행동(현재는 COMPLETED)만 저장하고, 시스템이
 * 판단하는 PENDING/MISSED는 저장하지 않는다. 조회는 저장된 기록과 스케줄 due 판정(ADR-0039)으로 파생한
 * 상태를 병합해 돌려준다.
 *
 * <p><b>백필 허용</b>(ADR-0038): 완료·완료 취소는 아직 오지 않은 날만 아니면 수행 기간 내 어느 날짜든
 * 가능하다. 깜빡한 하루가 영구히 MISSED로 굳지 않게 하려는 정책이며, MISSED는 저장된 사실이 아니라
 * 완료 기록이 없어서 계산된 값이라 백필하면 자동으로 사라진다.
 *
 * <p>인증 사진은 공통 모듈 {@link FileStorage}(S3)에 위임해 저장하고, MIME/크기/시그니처 검증은 이
 * 서비스 계층에서 수행한다. 완료 취소 시 사진 오브젝트는 커밋 이후 삭제하고, 업로드 후 트랜잭션이
 * 롤백되면 새 오브젝트를 보상 삭제해 DB 참조와 저장소 실체의 불일치를 방지한다. (#95/#142 패턴)
 */
@Slf4j
@Service
@Transactional(readOnly = true)
public class RoutineExecutionService {

    private static final String DIRECTORY = "routine-executions";
    private static final long MAX_FILE_SIZE_BYTES = 5L * 1024 * 1024; // 5MB
    private static final int MAX_MEMO_LENGTH = 500;
    private static final Set<String> ALLOWED_CONTENT_TYPES =
            Set.of("image/jpeg", "image/png", "image/webp");
    private static final byte[] PNG_SIGNATURE =
            {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};

    private final RoutineExecutionRepository executionRepository;
    private final RoutineRepository routineRepository;
    private final RoutineTemplateRepository templateRepository;
    private final FileStorage fileStorage;
    private final Clock clock;

    public RoutineExecutionService(RoutineExecutionRepository executionRepository,
                                   RoutineRepository routineRepository,
                                   RoutineTemplateRepository templateRepository,
                                   FileStorage fileStorage,
                                   Clock clock) {
        this.executionRepository = executionRepository;
        this.routineRepository = routineRepository;
        this.templateRepository = templateRepository;
        this.fileStorage = fileStorage;
        this.clock = clock;
    }

    /**
     * 완료 처리 — 지난 날짜를 포함해 수행 기간 내 날짜에 COMPLETED 행을 새로 만든다(sparse, 백필 허용).
     * 아직 오지 않은 날짜, 중단한 루틴은 거부하고, 이미 완료된 날이면 409.
     */
    @Transactional
    public ExecutionCompleteResult complete(CompleteExecutionCommand command) {
        Routine routine = getOwnedRoutineOrThrow(command.routineId(), command.userId());
        LocalDate date = command.scheduledDate();
        validateNotFuture(date);
        validateActive(routine);
        validateWithinPeriod(routine, date);

        RoutineTemplate template = getTemplateOrThrow(routine.getRoutineTemplateId());
        if (!ExecutionScheduleDeriver.isCompletable(template, date)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "오늘은 이 루틴의 수행 요일이 아닙니다.");
        }
        executionRepository.findByRoutineIdAndScheduledDate(routine.getId(), date)
                .ifPresent(existing -> {
                    throw new BusinessException(ErrorCode.EXECUTION_ALREADY_COMPLETED);
                });
        validateMemo(command.memo());

        String photoUrl = null;
        String photoObjectKey = null;
        if (command.hasPhoto()) {
            validatePhoto(command);
            StoredFile stored = fileStorage.upload(new FileUploadCommand(
                    DIRECTORY,
                    command.originalFilename(),
                    command.contentType(),
                    command.size(),
                    command.inputStream()));
            photoUrl = stored.url();
            photoObjectKey = stored.key();
            deleteAfterRollback(stored.key());
        }

        RoutineExecution execution;
        try {
            // saveAndFlush로 uq_re_routine_date 충돌을 이 메서드 안에서 즉시 받는다.
            // 선조회를 동시에 통과한 경합 요청 중 한쪽은 여기서 무결성 위반이 나고, 롤백되며 방금 업로드한
            // 사진은 deleteAfterRollback 동기화가 보상 삭제한다.
            execution = executionRepository.saveAndFlush(RoutineExecution.completed(
                    routine.getId(), command.userId(), date, LocalDateTime.now(clock),
                    photoUrl, photoObjectKey, command.memo()));
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(ErrorCode.EXECUTION_ALREADY_COMPLETED);
        }
        return ExecutionCompleteResult.from(execution);
    }

    /**
     * 완료 취소 — 완료 기록을 삭제해 PENDING(파생)으로 되돌린다. 잘못 남긴 인증을 지우는 동작이라 날짜
     * 제한이 없고, 중단한 루틴이어도 허용한다. 사진 오브젝트는 커밋 후 삭제한다.
     */
    @Transactional
    public ExecutionCompleteResult cancelComplete(Long routineId, Long userId, LocalDate date) {
        getOwnedRoutineOrThrow(routineId, userId);
        RoutineExecution execution = executionRepository.findByRoutineIdAndScheduledDate(routineId, date)
                .orElseThrow(() -> new BusinessException(ErrorCode.EXECUTION_NOT_FOUND));

        String photoObjectKey = execution.getPhotoObjectKey();
        executionRepository.delete(execution);
        deleteAfterCommit(photoObjectKey);

        return ExecutionCompleteResult.cancelled(routineId, date);
    }

    /**
     * 실행 기록 조회 — 저장된 완료 기록과 파생된 PENDING/MISSED를 병합해 날짜 내림차순으로 돌려준다.
     * 중단한 루틴은 완료 이력만 보이고 파생 상태는 만들지 않는다. status가 주어지면 해당 상태만 필터한다.
     */
    public List<ExecutionResult> getMyExecutions(Long userId, Long routineId, ExecutionStatus status,
                                                 LocalDate startDate, LocalDate endDate) {
        LocalDate today = LocalDate.now(clock);
        List<Routine> routines = routineRepository.findForExecutionDerivation(userId, routineId, startDate, endDate);
        if (routines.isEmpty()) {
            return List.of();
        }

        Map<Long, RoutineTemplate> templateByRoutineId = resolveTemplates(routines);
        Map<Long, Map<LocalDate, RoutineExecution>> completedByRoutine =
                executionRepository.findCompletedInRange(userId, routineId, startDate, endDate).stream()
                        .collect(Collectors.groupingBy(
                                RoutineExecution::getRoutineId,
                                Collectors.toMap(RoutineExecution::getScheduledDate, Function.identity())));

        List<ExecutionResult> results = new ArrayList<>();
        for (Routine routine : routines) {
            RoutineTemplate template = templateByRoutineId.get(routine.getId());
            if (template == null) {
                continue; // 템플릿이 사라진 예외적 상태 — 파생 불가하므로 건너뛴다.
            }
            String title = template.getTitle();
            Map<LocalDate, RoutineExecution> completed =
                    completedByRoutine.getOrDefault(routine.getId(), Map.of());

            LocalDate from = latest(startDate, routine.getStartedAt());
            LocalDate to = earliest(endDate, routine.getEndedAt());
            for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
                final LocalDate current = date;
                RoutineExecution row = completed.get(current);
                if (row != null) {
                    // 저장된 완료는 중단 여부와 무관하게 이력으로 남긴다.
                    results.add(ExecutionResult.from(row, title));
                } else if (routine.isActive()) {
                    // 중단한 루틴은 파생하지 않는다. 중단 시각을 기록하지 않아 "중단 이후의 과거"를
                    // 구분할 수 없으므로, 그만둔 뒤의 날짜에 MISSED를 붙이지 않도록 전 구간을 제외한다.
                    ExecutionScheduleDeriver.deriveStatus(template, current, today)
                            .ifPresent(derived -> results.add(
                                    ExecutionResult.derived(routine.getId(), title, current, derived)));
                }
            }
        }

        Stream<ExecutionResult> stream = results.stream();
        if (status != null) {
            stream = stream.filter(result -> result.status() == status);
        }
        return stream
                .sorted(Comparator.comparing(ExecutionResult::scheduledDate).reversed()
                        .thenComparing(ExecutionResult::routineId))
                .toList();
    }

    private Routine getOwnedRoutineOrThrow(Long routineId, Long userId) {
        return routineRepository.findByIdAndUserId(routineId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ROUTINE_NOT_FOUND));
    }

    private RoutineTemplate getTemplateOrThrow(Long templateId) {
        return templateRepository.findById(templateId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ROUTINE_TEMPLATE_NOT_FOUND));
    }

    /**
     * 아직 오지 않은 날짜는 완료할 수 없다. 지난 날짜는 허용한다(백필, ADR-0038).
     */
    private void validateNotFuture(LocalDate date) {
        if (date.isAfter(LocalDate.now(clock))) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "아직 오지 않은 날짜는 완료할 수 없습니다.");
        }
    }

    /**
     * 중단(비활성)한 루틴은 새로 완료할 수 없다. 완료 취소는 이미 남긴 기록을 지우는 동작이므로 중단
     * 이후에도 허용한다.
     */
    private void validateActive(Routine routine) {
        if (!routine.isActive()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "중단한 루틴은 완료할 수 없습니다.");
        }
    }

    private void validateWithinPeriod(Routine routine, LocalDate date) {
        if (date.isBefore(routine.getStartedAt()) || date.isAfter(routine.getEndedAt())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "루틴 수행 기간이 아닙니다.");
        }
    }

    private void validateMemo(String memo) {
        if (memo != null && memo.length() > MAX_MEMO_LENGTH) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "메모는 %d자 이하여야 합니다.".formatted(MAX_MEMO_LENGTH));
        }
    }

    /**
     * 파생 대상 루틴들이 참조하는 템플릿을 한 번의 조회로 모아 routineId → template 로 매핑한다.
     * 템플릿이 소프트 삭제됐어도 이력·파생 표시는 유지해야 하므로 is_deleted 필터 없이 조회한다.
     */
    private Map<Long, RoutineTemplate> resolveTemplates(List<Routine> routines) {
        List<Long> templateIds = routines.stream()
                .map(Routine::getRoutineTemplateId)
                .distinct()
                .toList();
        Map<Long, RoutineTemplate> templateById = templateRepository.findAllById(templateIds).stream()
                .collect(Collectors.toMap(RoutineTemplate::getId, Function.identity()));

        Map<Long, RoutineTemplate> byRoutineId = new java.util.HashMap<>();
        for (Routine routine : routines) {
            RoutineTemplate template = templateById.get(routine.getRoutineTemplateId());
            if (template != null) {
                byRoutineId.put(routine.getId(), template);
            }
        }
        return byRoutineId;
    }

    private void validatePhoto(CompleteExecutionCommand command) {
        if (command.size() > MAX_FILE_SIZE_BYTES) {
            throw new BusinessException(ErrorCode.FILE_SIZE_EXCEEDED);
        }
        String contentType = command.contentType();
        if (contentType == null || !ALLOWED_CONTENT_TYPES.contains(contentType.toLowerCase(Locale.ROOT))) {
            throw new BusinessException(ErrorCode.UNSUPPORTED_IMAGE_TYPE);
        }
        if (!hasValidImageSignature(contentType, command.photoBytes())) {
            throw new BusinessException(ErrorCode.UNSUPPORTED_IMAGE_TYPE);
        }
    }

    private void deleteAfterCommit(String objectKey) {
        if (objectKey == null) {
            return;
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            deleteQuietly(objectKey);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                deleteQuietly(objectKey);
            }
        });
    }

    private void deleteAfterRollback(String objectKey) {
        if (objectKey == null || !TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) {
                    deleteQuietly(objectKey);
                }
            }
        });
    }

    private void deleteQuietly(String objectKey) {
        if (objectKey == null) {
            return;
        }
        try {
            fileStorage.delete(objectKey);
        } catch (RuntimeException e) {
            log.warn("루틴 인증 사진 오브젝트 삭제 실패 (objectKey={}). 고아 파일이 남을 수 있습니다.", objectKey, e);
        }
    }

    private boolean hasValidImageSignature(String contentType, byte[] bytes) {
        return switch (contentType.toLowerCase(Locale.ROOT)) {
            case "image/jpeg" -> isJpeg(bytes);
            case "image/png" -> isPng(bytes);
            case "image/webp" -> isWebp(bytes);
            default -> false;
        };
    }

    private boolean isJpeg(byte[] bytes) {
        return bytes.length >= 3
                && (bytes[0] & 0xFF) == 0xFF
                && (bytes[1] & 0xFF) == 0xD8
                && (bytes[2] & 0xFF) == 0xFF;
    }

    private boolean isPng(byte[] bytes) {
        if (bytes.length < PNG_SIGNATURE.length) {
            return false;
        }
        for (int i = 0; i < PNG_SIGNATURE.length; i++) {
            if (bytes[i] != PNG_SIGNATURE[i]) {
                return false;
            }
        }
        return true;
    }

    private boolean isWebp(byte[] bytes) {
        return bytes.length >= 12
                && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P';
    }

    private static LocalDate latest(LocalDate a, LocalDate b) {
        return a.isAfter(b) ? a : b;
    }

    private static LocalDate earliest(LocalDate a, LocalDate b) {
        return a.isBefore(b) ? a : b;
    }
}
