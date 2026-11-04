package com.routinely.routine_service.application.inbox;

import com.routinely.routine_service.domain.inbox.RoutineInbox;

/**
 * Inbox 이벤트 처리기 — {@code eventType} 하나를 맡는다. {@link RoutineInboxProcessor}가 {@link #eventType()}을
 * 키로 찾아 호출한다. 소비 토픽이 늘면 구현체만 추가하고 Processor는 고치지 않는다. (#157)
 *
 * <p><b>트랜잭션을 새로 열지 않는다.</b> {@link #handle}은 {@code processInbox()}의 트랜잭션 안에서 불린다 —
 * 도메인 저장 · Outbox INSERT · Inbox PROCESSED 전이가 한 번에 커밋되거나 함께 롤백되어야 한다.
 * 구현체에 {@code REQUIRES_NEW}를 붙이면 이 원자성이 깨지고, {@code MANDATORY}인 Outbox 발행 창구와도 어긋난다.
 *
 * <p>처리할 수 없으면 <b>예외를 던진다.</b> 스케줄러가 별도 트랜잭션으로 실패를 기록하고 재시도 한도 후 FAILED로 남긴다.
 */
public interface InboxEventHandler {

    /** 처리할 이벤트 타입 — Kafka 토픽명과 같다({@code KafkaTopics}). 구현체끼리 겹치면 앱이 기동하지 않는다. */
    String eventType();

    /** Inbox 한 건을 처리한다. 성공 시 PROCESSED 전이는 호출부가 한다. */
    void handle(RoutineInbox inbox);
}
