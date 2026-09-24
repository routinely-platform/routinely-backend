package com.routinely.core.constant;

public interface KafkaTopics {

    // Routine
    String ROUTINE_EXECUTION_COMPLETED = "routine.execution.completed";

    String ROUTINE_EXECUTION_CANCELLED = "routine.execution.cancelled";
    String ROUTINE_NOTIFICATION_SCHEDULED = "routine.notification.scheduled";

    // Challenge
    String CHALLENGE_MEMBER_JOINED = "challenge.member.joined";
    String CHALLENGE_MEMBER_LEFT   = "challenge.member.left";

    // Chat
    String CHAT_MESSAGE_CREATED = "chat.message.created";
}
