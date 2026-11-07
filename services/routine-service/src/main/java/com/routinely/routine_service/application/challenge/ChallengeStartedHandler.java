package com.routinely.routine_service.application.challenge;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.routinely.core.constant.KafkaTopics;
import com.routinely.core.exception.BusinessException;
import com.routinely.core.schedule.ScheduleType;
import com.routinely.core.schedule.Weekdays;
import com.routinely.routine_service.application.event.RoutineEventPublisher;
import com.routinely.routine_service.application.inbox.InboxEventHandler;
import com.routinely.routine_service.domain.definition.RoutineDefinition;
import com.routinely.routine_service.domain.inbox.RoutineInbox;
import com.routinely.routine_service.domain.routine.Routine;
import com.routinely.routine_service.domain.routine.RoutineRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

import static com.routinely.core.exception.ErrorCode.INTERNAL_SERVER_ERROR;

/**
 * {@code challenge.started} 처리기 — 시작 시점 멤버마다 챌린지 루틴 인스턴스를 만든다. (#157, ADR-0032 · ADR-0044)
 *
 * <p><b>정의는 페이로드에서 복사한다.</b> 템플릿도 challenge-service도 조회하지 않는다 — 페이로드가 자기완결적이다
 * (event-spec.md §3). 그래서 이 클래스는 템플릿 저장소나 gRPC 클라이언트를 갖지 않는다.
 *
 * <p><b>필수 필드는 여기서 검증한다</b>(리스너가 아니라). 실패하면 예외를 던지고, Inbox에 원문과 {@code last_error}가
 * 남는다. 특히 {@code endedAt} — 비어 있으면 완료가 전부 거부되는 루틴이 생긴다({@link Routine#forChallenge}).
 *
 * <p><b>트랜잭션을 새로 열지 않는다</b>({@link Propagation#MANDATORY}). {@code processInbox()}의 트랜잭션에 얹혀
 * 멤버 N명의 루틴 · Outbox N건 · Inbox PROCESSED가 한 번에 커밋된다. 한 명이라도 실패하면 전부 롤백되므로
 * "앞의 몇 명만 만들어진" 상태가 없고, 재시도해도 중복이 생기지 않는다.
 * {@code REQUIRES_NEW}로 바꿔도 발행 창구({@code MANDATORY})는 새 트랜잭션에 참여해 정상 동작한다 — 그래서 더 위험하다.
 * 루틴이 커밋된 뒤 Inbox 커밋이 실패하면 재처리가 UNIQUE에 막혀 "루틴은 있는데 FAILED"가 남는다.
 *
 * <p>실행 기록({@code routine_executions})은 만들지 않는다 — 희소 저장(ADR-0038).
 * {@code challengeName} · {@code leaderUserId}는 쓰지 않는다(채팅·알림용).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChallengeStartedHandler implements InboxEventHandler {

    private final RoutineRepository routineRepository;
    private final RoutineEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

    @Override
    public String eventType() {
        return KafkaTopics.CHALLENGE_STARTED;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void handle(RoutineInbox inbox) {
        ChallengeStartedPayload payload = read(inbox);
        int created = createMemberRoutines(payload);
        log.info("[Inbox] 챌린지 루틴 생성 - inboxId: {}, challengeId: {}, 생성: {}건",
                inbox.getId(), payload.challengeId(), created);
    }

    /**
     * 멤버마다 챌린지 루틴을 만들고 알림 예약 이벤트를 Outbox에 쌓는다.
     *
     * @return 만든 루틴 수
     * @throws BusinessException 필수 필드가 없거나 정의가 스케줄 규칙에 맞지 않을 때 — Inbox 실패로 기록된다
     */
    int createMemberRoutines(ChallengeStartedPayload payload) {
        // ① 필수 필드 — endedAt이 빠지면 완료가 전부 거부되는 루틴이 생긴다(Routine.forChallenge)
        require(payload.challengeId(), "challengeId", payload);
        require(payload.routineTitle(), "routineTitle", payload);
        require(payload.categoryCode(), "categoryCode", payload);
        require(payload.scheduleType(), "scheduleType", payload);
        require(payload.startedAt(), "startedAt", payload);
        require(payload.endedAt(), "endedAt", payload);
        if (payload.members() == null || payload.members().isEmpty()) {
            throw invalid("members가 비어 있습니다", payload);
        }

        // ② 원문 → 도메인 타입. 형식 오류도 Inbox 실패로 남도록 BusinessException으로 바꾼다
        LocalDate startedAt;
        LocalDate endedAt;
        ScheduleType scheduleType;
        Short daysOfWeek;
        try {
            startedAt = LocalDate.parse(payload.startedAt());
            endedAt = LocalDate.parse(payload.endedAt());
            scheduleType = ScheduleType.valueOf(payload.scheduleType());
            daysOfWeek = payload.daysOfWeek() == null ? null : Weekdays.toBitmask(payload.daysOfWeek());
        } catch (RuntimeException e) {
            throw invalid("형식이 잘못된 필드가 있습니다(" + e.getMessage() + ")", payload);
        }

        // ③ 정의는 루프 밖에서 한 번 — 잘못된 조합이면 아무도 저장되기 전에 실패한다
        RoutineDefinition definition = RoutineDefinition.of(payload.routineTitle(), payload.categoryCode(),
                scheduleType, daysOfWeek, payload.targetCount());

        // ④ 멤버마다 저장 → ID 확보 → 알림 예약 Outbox(aggregate_id = routineId)
        for (ChallengeStartedPayload.Member member : payload.members()) {
            if (member == null || member.userId() == null) {
                throw invalid("userId가 없는 멤버가 있습니다", payload);
            }
            Routine routine = routineRepository.save(Routine.forChallenge(payload.challengeId(), member.userId(),
                    definition.copy(), startedAt, endedAt));
            eventPublisher.publishNotificationScheduled(routine);
        }
        return payload.members().size();
    }

    private static void require(Object value, String field, ChallengeStartedPayload payload) {
        if (value == null || (value instanceof String s && s.isBlank())) {
            throw invalid("필수 필드 " + field + "가 없습니다", payload);
        }
    }

    private static BusinessException invalid(String reason, ChallengeStartedPayload payload) {
        return new BusinessException(INTERNAL_SERVER_ERROR,
                "challenge.started 처리 불가 — %s. challengeId=%s".formatted(reason, payload.challengeId()));
    }

    /** Inbox의 원문을 페이로드로 읽는다. 리스너가 한 번 읽은 JSON이라 여기서 실패하는 일은 드물다. */
    private ChallengeStartedPayload read(RoutineInbox inbox) {
        try {
            return objectMapper.readValue(inbox.getPayload(), ChallengeStartedPayload.class);
        } catch (JsonProcessingException e) {
            throw new BusinessException(INTERNAL_SERVER_ERROR,
                    "challenge.started 페이로드를 읽을 수 없습니다. inboxId=" + inbox.getId(), e);
        }
    }
}
