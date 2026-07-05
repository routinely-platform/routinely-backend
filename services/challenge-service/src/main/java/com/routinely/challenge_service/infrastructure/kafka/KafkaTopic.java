package com.routinely.challenge_service.infrastructure.kafka;

public final class KafkaTopic {

    private KafkaTopic() {}

    public static final String CHALLENGE_CREATED       = "challenge.created";
    public static final String CHALLENGE_MEMBER_JOINED = "challenge.member.joined";
    public static final String CHALLENGE_MEMBER_LEFT   = "challenge.member.left";
    public static final String CHALLENGE_STARTED       = "challenge.started";

    // 소비 토픽 — routine-service(#61)가 발행하는 루틴 완료 이벤트. 랭킹 집계에 사용한다. (ADR-0028)
    public static final String ROUTINE_EXECUTION_COMPLETED = "routine.execution.completed";
}
