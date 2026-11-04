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
 * "앞의 몇 명만 만들어진" 상태가 없고, 재시도해도 중복이 생기지 않는다. 발행 창구
 * {@code publishNotificationScheduled}도 {@code MANDATORY}라 경계를 새로 열면 바로 깨진다.
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
        // TODO(human): 페이로드 → 멤버별 챌린지 루틴. 순서대로 네 단계다.
        //   ① 필수 필드 검증 — challengeId · routineTitle · categoryCode · scheduleType · startedAt · endedAt,
        //      members는 비어 있지 않아야 하고 각 멤버의 userId도 있어야 한다.
        //      실패는 invalid(사유, payload)로 던진다. 사유에 필드명을 넣는다(테스트가 메시지에서 찾는다).
        //   ② 변환 — 날짜 문자열 → LocalDate, scheduleType 문자열 → ScheduleType,
        //      daysOfWeek 코드 리스트(["MON","WED","FRI"]) → Weekdays.toBitmask() 비트마스크(null이면 null).
        //      형식 오류(DateTimeParseException · IllegalArgumentException)도 invalid(...)로 바꿔 던진다.
        //   ③ 정의 — RoutineDefinition.of(...)가 유형 ↔ 요일·횟수 조합을 교차 검증한다(ck_routines_schedule 미러).
        //      루프 밖에서 한 번 만든다 — 잘못된 정의면 아무도 저장되기 전에 실패해야 한다.
        //   ④ 멤버 N명 루프 — Routine.forChallenge(...)를 save해 ID를 확보한 뒤
        //      eventPublisher.publishNotificationScheduled(저장된 루틴)를 부른다. 만든 수를 반환한다.
        // HINT: Outbox aggregate_id가 routineId라 save가 publish보다 먼저다. save의 반환값을 넘긴다.
        // HINT: 정의는 @Embeddable 값 객체다. 멤버마다 definition.copy()로 각자의 사본을 갖게 한다(ADR-0040).
        // HINT: 이 메서드에 트랜잭션을 붙이지 않는다 — handle()의 MANDATORY가 processInbox()의 트랜잭션에 얹는다.
        // REFERENCE: docs/requirements/event-spec.md §3 · Routine.forChallenge · RoutineDefinition.of
        //            · ChallengeStartedHandlerTest · ChallengeStartedIntegrationTest
        throw new UnsupportedOperationException("TODO(human): challenge.started 멤버별 루틴 생성");
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
