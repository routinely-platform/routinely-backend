package com.routinely.challenge_service.infrastructure.kafka;

public final class KafkaTopic {

    private KafkaTopic() {}

    public static final String CHALLENGE_CREATED       = "challenge.created";
    public static final String CHALLENGE_MEMBER_JOINED = "challenge.member.joined";
    public static final String CHALLENGE_MEMBER_LEFT   = "challenge.member.left";
    public static final String CHALLENGE_STARTED       = "challenge.started";
}
