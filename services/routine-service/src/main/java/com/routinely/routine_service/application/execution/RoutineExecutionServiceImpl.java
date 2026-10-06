package com.routinely.routine_service.application.execution;

import com.routinely.core.exception.BusinessException;
import com.routinely.core.exception.ErrorCode;
import com.routinely.routine_service.application.event.RoutineEventPublisher;
import com.routinely.routine_service.application.execution.dto.CompleteExecutionCommand;
import com.routinely.routine_service.application.execution.dto.ExecutionCompleteResult;
import com.routinely.routine_service.application.execution.dto.ExecutionResult;
import com.routinely.routine_service.domain.definition.RoutineDefinition;
import com.routinely.routine_service.domain.execution.ExecutionStatus;
import com.routinely.routine_service.domain.execution.RoutineExecution;
import com.routinely.routine_service.domain.execution.RoutineExecutionRepository;
import com.routinely.routine_service.domain.feed.FeedCard;
import com.routinely.routine_service.domain.feed.FeedCardRepository;
import com.routinely.routine_service.domain.routine.Routine;
import com.routinely.routine_service.domain.routine.RoutineRepository;
import com.routinely.storage.FileStorage;
import com.routinely.storage.FileUploadCommand;
import com.routinely.storage.StoredFile;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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
 * <p>인증 사진은 공통 모듈 {@link FileStorage}(S3)에 위임해 저장하고, MIME/크기/시그니처 검증은 요청 계층에서 수행한다. 완료 취소 시 사진 오브젝트는 커밋 이후 삭제하고, 업로드 후 트랜잭션이
 * 롤백되면 새 오브젝트를 보상 삭제해 DB 참조와 저장소 실체의 불일치를 방지한다. (#95/#142 패턴)
 */
@Slf4j
@RequiredArgsConstructor
@Service
@Transactional(readOnly = true)
public class RoutineExecutionServiceImpl implements RoutineExecutionService {

    private static final String DIRECTORY = "routine-executions";
    private final RoutineExecutionRepository executionRepository;
    private final RoutineRepository routineRepository;
    private final FileStorage fileStorage;
    private final Clock clock;
    private final FeedCardRepository feedCardRepository;
    private final AcceptedCountCalculator acceptedCountCalculator;
    private final RoutineEventPublisher eventPublisher;

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

        if (!ExecutionScheduleDeriver.isCompletable(routine.getDefinition(), date)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "오늘은 이 루틴의 수행 요일이 아닙니다.");
        }
        executionRepository.findByRoutineIdAndScheduledDate(routine.getId(), date)
                .ifPresent(existing -> {
                    throw new BusinessException(ErrorCode.EXECUTION_ALREADY_COMPLETED);
                });

        String photoUrl = null;
        String photoObjectKey = null;
        if (command.hasPhoto()) {
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
                    routine.getId(), command.userId(), date, LocalDateTime.now(clock)));
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(ErrorCode.EXECUTION_ALREADY_COMPLETED);
        }
        FeedCard card = feedCardRepository.save(FeedCard.builder()
                .routineExecutionId(execution.getId()).userId(command.userId())
                .challengeId(routine.getChallengeId()).routineTitle(routine.getDefinition().getTitle())
                .scheduledDate(date).photoUrl(photoUrl).photoObjectKey(photoObjectKey).memo(command.memo())
                .build());
        eventPublisher.publishCompleted(routine, execution.getId(), date, rankingSnapshot(routine));
        return ExecutionCompleteResult.from(execution, card);
    }

    /**
     * 완료 취소 — 완료 기록을 삭제해 PENDING(파생)으로 되돌린다. 잘못 남긴 인증을 지우는 동작이라 날짜
     * 제한이 없고, 중단한 루틴이어도 허용한다. 사진 오브젝트는 커밋 후 삭제한다.
     */
    @Transactional
    public ExecutionCompleteResult cancelComplete(Long routineId, Long userId, LocalDate date) {
        Routine routine = getOwnedRoutineOrThrow(routineId, userId);
        RoutineExecution execution = executionRepository.findByRoutineIdAndScheduledDate(routineId, date)
                .orElseThrow(() -> new BusinessException(ErrorCode.EXECUTION_NOT_FOUND));

        FeedCard card = feedCardRepository.findByRoutineExecutionId(execution.getId()).orElse(null);
        String photoObjectKey = card == null ? null : card.getPhotoObjectKey();
        if (card != null) {
            feedCardRepository.delete(card);
            feedCardRepository.flush();
        }
        executionRepository.delete(execution);
        executionRepository.flush();
        eventPublisher.publishCancelled(routine, execution.getId(), date, rankingSnapshot(routine));
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

        Map<Long, Map<LocalDate, RoutineExecution>> completedByRoutine =
                executionRepository.findCompletedInRange(userId, routineId, startDate, endDate).stream()
                        .collect(Collectors.groupingBy(
                                RoutineExecution::getRoutineId,
                                Collectors.toMap(RoutineExecution::getScheduledDate, Function.identity())));

        List<Long> executionIds = completedByRoutine.values().stream()
                .flatMap(rows -> rows.values().stream()).map(RoutineExecution::getId).toList();
        Map<Long, FeedCard> cards = executionIds.isEmpty() ? Map.of() : feedCardRepository
                .findByRoutineExecutionIdIn(executionIds).stream()
                .collect(Collectors.toMap(FeedCard::getRoutineExecutionId, Function.identity()));
        List<ExecutionResult> results = new ArrayList<>();
        for (Routine routine : routines) {
            // 루틴이 자기 정의를 갖는다 — 템플릿이 지워졌어도 파생이 된다 (ADR-0040).
            RoutineDefinition definition = routine.getDefinition();
            String title = definition.getTitle();
            Map<LocalDate, RoutineExecution> completed =
                    completedByRoutine.getOrDefault(routine.getId(), Map.of());

            LocalDate from = latest(startDate, routine.getStartedAt());
            LocalDate to = derivationEnd(routine, endDate);
            for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
                final LocalDate current = date;
                RoutineExecution row = completed.get(current);
                if (row != null) {
                    // 저장된 완료는 중단 여부와 무관하게 이력으로 남긴다.
                    results.add(ExecutionResult.from(row, title, cards.get(row.getId())));
                } else if (routine.isActive()) {
                    // 중단한 루틴은 파생하지 않는다. 중단 시각을 기록하지 않아 "중단 이후의 과거"를
                    // 구분할 수 없으므로, 그만둔 뒤의 날짜에 MISSED를 붙이지 않도록 전 구간을 제외한다.
                    ExecutionScheduleDeriver.deriveStatus(definition, current, today)
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

    @Transactional
    public List<ExecutionCompleteResult> bulkComplete(Long userId, LocalDate date, List<Long> routineIds) {
        // ID 순으로 잠가 겹치는 다중 요청의 교착을 방지한다. 한 건 실패하면 전체 롤백한다.
        return routineIds.stream().sorted().map(id -> complete(new CompleteExecutionCommand(
                id, userId, date, null, null, null, null))).toList();
    }

    /** 챌린지 루틴만 랭킹 스냅샷을 계산한다. 개인 루틴은 랭킹 대상이 아니다. */
    private AcceptedCount rankingSnapshot(Routine routine) {
        return routine.isChallengeRoutine() ? acceptedCountCalculator.calculate(routine) : null;
    }

    private Routine getOwnedRoutineOrThrow(Long routineId, Long userId) {
        return routineRepository.findLockedByIdAndUserId(routineId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ROUTINE_NOT_FOUND));
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

    /**
     * 수행 기간 검증. 종료일이 NULL이면 무기한이므로 <b>상한 검사를 건너뛴다</b> (ADR-0041).
     */
    private void validateWithinPeriod(Routine routine, LocalDate date) {
        boolean beforeStart = date.isBefore(routine.getStartedAt());
        boolean afterEnd = !routine.hasNoEndDate() && date.isAfter(routine.getEndedAt());
        if (beforeStart || afterEnd) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "루틴 수행 기간이 아닙니다.");
        }
    }

    /**
     * 파생 범위의 끝. 종료일이 NULL(무기한)이면 조회 종료일까지 전개한다 (ADR-0041).
     *
     * <p>이 분기를 빠뜨리면 무기한 루틴에서 NPE가 나거나 파생 구간이 통째로 비어 버린다.
     */
    private LocalDate derivationEnd(Routine routine, LocalDate queryEnd) {
        return routine.hasNoEndDate() ? queryEnd : earliest(queryEnd, routine.getEndedAt());
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

    private static LocalDate latest(LocalDate a, LocalDate b) {
        return a.isAfter(b) ? a : b;
    }

    private static LocalDate earliest(LocalDate a, LocalDate b) {
        return a.isBefore(b) ? a : b;
    }
}
