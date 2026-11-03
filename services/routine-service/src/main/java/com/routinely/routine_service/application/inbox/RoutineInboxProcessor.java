package com.routinely.routine_service.application.inbox;

import com.routinely.core.exception.BusinessException;
import com.routinely.routine_service.domain.inbox.InboxStatus;
import com.routinely.routine_service.domain.inbox.RoutineInbox;
import com.routinely.routine_service.domain.inbox.RoutineInboxRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static com.routinely.core.exception.ErrorCode.INTERNAL_SERVER_ERROR;

/**
 * routine_inbox 한 건을 처리한다.
 *
 * <p><b>지금은 처리할 이벤트가 없다.</b> {@code challenge.created} 소비를 제거하면서(ADR-0044, #167)
 * routine-service가 구독하는 토픽이 0개가 됐다. 그래도 Inbox 테이블과 스케줄러는 남겨 둔다 —
 * {@code challenge.started}(#157) · {@code challenge.member.left}(#158) 소비가 이 경로를 다시 쓴다.
 *
 * <p>⚠️ 그때 {@code eventType}별 처리기로 라우팅해야 한다. 그 전까지 들어온 메시지는 PROCESSED로 넘기지 않고
 * <b>실패로 기록</b>한다 — 모르는 이벤트가 조용히 사라지지 않고 재시도 한도 후 FAILED로 남아 보이게 하기 위해서다.
 *
 * <p>각 메서드는 독립 트랜잭션이다. 처리 실패 시 처리 트랜잭션은 롤백되고 스케줄러가 별도 트랜잭션으로
 * {@link #recordFailure}를 호출해 retry_count를 올린다. MAX_RETRY 이내면 RECEIVED를 유지해 재시도하고,
 * 초과 시에만 FAILED로 전이한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RoutineInboxProcessor {

    /** 처리 재시도 한도 — 초과 시 Inbox를 FAILED로 전이한다. (challenge_outbox와 동일) */
    static final int MAX_RETRY = 5;
    /** last_error에 저장할 최대 길이 — 과도한 스택트레이스 적재를 방지한다. */
    static final int MAX_ERROR_LENGTH = 1000;

    private final RoutineInboxRepository inboxRepository;

    /**
     * RECEIVED 상태의 Inbox 한 건을 처리한다. 처리기가 없는 이벤트면 예외를 던져 스케줄러가 실패로 기록하게 한다.
     */
    @Transactional
    public void processInbox(Long inboxId) {
        RoutineInbox inbox = inboxRepository.findById(inboxId)
                .orElseThrow(() -> new BusinessException(INTERNAL_SERVER_ERROR,
                        "처리 대상 Inbox를 찾을 수 없습니다. inboxId=" + inboxId));

        if (inbox.getStatus() != InboxStatus.RECEIVED) {
            log.info("[Inbox] 이미 처리된 메시지여서 건너뜁니다 - inboxId: {}, status: {}",
                    inboxId, inbox.getStatus());
            return;
        }

        throw new BusinessException(INTERNAL_SERVER_ERROR,
                "처리기가 없는 이벤트입니다. eventType=" + inbox.getEventType());
    }

    /**
     * 처리에 실패한 Inbox의 재시도 횟수를 증가시키고 마지막 오류를 기록한다. (별도 트랜잭션)
     */
    @Transactional
    public void recordFailure(Long inboxId, String errorMessage) {
        inboxRepository.findById(inboxId)
                .ifPresent(inbox -> inbox.recordFailure(MAX_RETRY, truncate(errorMessage)));
    }

    private String truncate(String message) {
        if (message == null || message.length() <= MAX_ERROR_LENGTH) {
            return message;
        }
        return message.substring(0, MAX_ERROR_LENGTH);
    }
}
