package com.routinely.routine_service.application.inbox;

import com.routinely.core.exception.BusinessException;
import com.routinely.routine_service.domain.inbox.InboxStatus;
import com.routinely.routine_service.domain.inbox.RoutineInbox;
import com.routinely.routine_service.domain.inbox.RoutineInboxRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.routinely.core.exception.ErrorCode.INTERNAL_SERVER_ERROR;

/**
 * routine_inbox 한 건을 처리한다.
 *
 * <p><b>{@code eventType}으로 처리기를 골라 위임한다.</b> 처리기는 {@link InboxEventHandler} 구현체이고
 * 이 클래스는 어떤 이벤트가 있는지 모른다 — 토픽이 늘어도(#158 {@code challenge.member.left}) 여기는 그대로다.
 *
 * <p>처리기가 없는 {@code eventType}은 PROCESSED로 넘기지 않고 <b>예외를 던져 실패로 기록</b>한다 —
 * 모르는 이벤트가 조용히 사라지지 않고 재시도 한도 후 FAILED로 남아 보이게 하기 위해서다.
 *
 * <p>처리기는 {@link #processInbox}의 트랜잭션 안에서 실행된다. 처리기의 도메인 저장 · Outbox INSERT와
 * 이 Inbox의 PROCESSED 전이가 한 트랜잭션이라, 중간에 실패하면 전부 롤백되고 재처리해도 중복이 생기지 않는다.
 *
 * <p>각 메서드는 독립 트랜잭션이다. 처리 실패 시 처리 트랜잭션은 롤백되고 스케줄러가 별도 트랜잭션으로
 * {@link #recordFailure}를 호출해 retry_count를 올린다. MAX_RETRY 이내면 RECEIVED를 유지해 재시도하고,
 * 초과 시에만 FAILED로 전이한다.
 */
@Slf4j
@Service
public class RoutineInboxProcessor {

    /** 처리 재시도 한도 — 초과 시 Inbox를 FAILED로 전이한다. (challenge_outbox와 동일) */
    static final int MAX_RETRY = 5;
    /** last_error에 저장할 최대 길이 — 과도한 스택트레이스 적재를 방지한다. */
    static final int MAX_ERROR_LENGTH = 1000;

    private final RoutineInboxRepository inboxRepository;
    private final Map<String, InboxEventHandler> handlers;
    private final Clock clock;

    /**
     * ⚠️ {@code @RequiredArgsConstructor}를 쓰지 않는다 — 받은 처리기 목록을 {@code eventType} 키 Map으로
     * 바꿔야 해서 생성자를 직접 쓴다(Lombok 조합 규칙의 예외).
     *
     * <p>{@code Map<String, InboxEventHandler>}를 바로 주입받지 않는 이유: 스프링이 채워 주는 키는
     * <b>빈 이름</b>({@code challengeStartedHandler})이지 {@code eventType}({@code challenge.started})이 아니다.
     * 그래서 {@code List}로 받아 직접 조립한다.
     *
     * <p>두 처리기가 같은 {@code eventType}을 말하면 {@link Collectors#toUnmodifiableMap}이 {@link IllegalStateException}을
     * 던져 <b>앱이 기동하지 않는다</b> — 어느 쪽이 불릴지 모르는 상태로 뜨는 것보다 낫다(의도된 fail-fast).
     */
    public RoutineInboxProcessor(RoutineInboxRepository inboxRepository,
                                 List<InboxEventHandler> handlers,
                                 Clock clock) {
        this.inboxRepository = inboxRepository;
        this.handlers = handlers.stream()
                .collect(Collectors.toUnmodifiableMap(InboxEventHandler::eventType, Function.identity()));
        this.clock = clock;
    }

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

        InboxEventHandler handler = handlers.get(inbox.getEventType());
        if (handler == null) {
            throw new BusinessException(INTERNAL_SERVER_ERROR,
                    "처리기가 없는 이벤트입니다. eventType=" + inbox.getEventType());
        }

        handler.handle(inbox);
        inbox.markProcessed(LocalDateTime.now(clock));
        log.info("[Inbox] 처리 완료 - inboxId: {}, eventType: {}, aggregateId: {}",
                inboxId, inbox.getEventType(), inbox.getAggregateId());
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
