package com.routinely.routine_service.application.challenge;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.routinely.core.exception.BusinessException;
import com.routinely.routine_service.domain.inbox.InboxStatus;
import com.routinely.routine_service.domain.inbox.RoutineInbox;
import com.routinely.routine_service.domain.inbox.RoutineInboxRepository;
import com.routinely.routine_service.domain.template.RepeatType;
import com.routinely.routine_service.domain.template.RoutineTemplate;
import com.routinely.routine_service.domain.template.RoutineTemplateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;

import static com.routinely.core.exception.ErrorCode.INTERNAL_SERVER_ERROR;

/**
 * Inbox에 저장된 {@code challenge.created} 이벤트를 처리해 챌린지 연결 루틴 템플릿을 생성한다.
 *
 * <p>각 메시지는 독립 트랜잭션으로 처리된다({@link #processInbox} / {@link #recordFailure} 모두 별도 트랜잭션).
 * 한 건의 처리 실패가 다른 건의 처리에 영향을 주지 않으며, 실패 시 처리 트랜잭션은 롤백되고
 * 스케줄러가 별도 트랜잭션으로 {@link #recordFailure}를 호출해 retry_count를 올린다.
 * MAX_RETRY를 초과하기 전까지는 RECEIVED를 유지해 재시도하고, 초과 시에만 FAILED로 전이한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChallengeRoutineTemplateService {

    /** 처리 재시도 한도 — 초과 시 Inbox를 FAILED로 전이한다. (challenge_outbox와 동일) */
    static final int MAX_RETRY = 5;
    /** last_error에 저장할 최대 길이 — 과도한 스택트레이스 적재를 방지한다. */
    static final int MAX_ERROR_LENGTH = 1000;

    private final RoutineInboxRepository inboxRepository;
    private final RoutineTemplateRepository templateRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    /**
     * RECEIVED 상태의 Inbox 한 건을 처리해 템플릿을 생성하고 PROCESSED로 전이한다.
     * 처리 실패 시 예외를 전파하여 호출자(스케줄러)가 FAILED 처리하도록 한다.
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

        ChallengeCreatedPayload payload = deserialize(inbox.getPayload());
        createTemplateIfAbsent(payload);
        inbox.markProcessed(LocalDateTime.now(clock));

        log.info("[Inbox] 처리 완료 - inboxId: {}, challengeId: {}", inboxId, payload.challengeId());
    }

    /**
     * 처리에 실패한 Inbox의 재시도 횟수를 증가시키고 마지막 오류를 기록한다. (별도 트랜잭션)
     * MAX_RETRY 이내면 RECEIVED를 유지해 다음 폴링에서 재시도되고, 초과 시 FAILED로 전이한다.
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

    private void createTemplateIfAbsent(ChallengeCreatedPayload payload) {
        if (templateRepository.existsByChallengeId(payload.challengeId())) {
            log.info("[Inbox] 챌린지 템플릿이 이미 존재하여 생성을 건너뜁니다 - challengeId: {}",
                    payload.challengeId());
            return;
        }
        templateRepository.save(RoutineTemplate.forChallenge(
                payload.creatorUserId(),
                payload.challengeId(),
                payload.routineTitle(),
                payload.categoryCode(),
                RepeatType.valueOf(payload.repeatType()),
                payload.repeatValue()
        ));
    }

    private ChallengeCreatedPayload deserialize(String payload) {
        try {
            return objectMapper.readValue(payload, ChallengeCreatedPayload.class);
        } catch (JsonProcessingException e) {
            throw new BusinessException(INTERNAL_SERVER_ERROR,
                    "challenge.created Payload 역직렬화에 실패했습니다.");
        }
    }
}
