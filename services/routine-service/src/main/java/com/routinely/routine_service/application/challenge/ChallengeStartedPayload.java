package com.routinely.routine_service.application.challenge;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * challenge-service가 발행하는 {@code challenge.started} 이벤트 Payload. (event-spec.md §3, ADR-0044)
 *
 * <p><b>자기완결적이다.</b> 루틴 정의 · 기간 · 시작 시점 멤버가 모두 실려 있어 routine-service는 추가 조회 없이
 * 멤버별 루틴을 만든다.
 *
 * <p>필드는 <b>원문 그대로</b> 받는다(날짜는 {@code yyyy-MM-dd} 문자열, 요일은 MON~SUN 코드 리스트).
 * 리스너는 이 값을 따지지 않고 Inbox에 저장만 한다 — 필수 여부 · 형식 검증은 처리 시점의 핸들러가 한다.
 * 그래야 잘못된 메시지가 {@code routine_inbox}에 원문과 {@code last_error}로 남는다.
 *
 * <p>{@code challengeName} · {@code leaderUserId}는 이 서비스가 쓰지 않는다(채팅방 OWNER · 알림용).
 * 계약을 그대로 옮겨 두기 위해 담는다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ChallengeStartedPayload(
        String eventId,
        String occurredAt,
        Long challengeId,
        String challengeName,
        String startedAt,
        String endedAt,
        String routineTitle,
        String categoryCode,
        String scheduleType,
        List<String> daysOfWeek,
        Integer targetCount,
        Long leaderUserId,
        List<Member> members
) {

    /** 시작 시점 ACTIVE 멤버. {@code joinedAt}은 참여일(Asia/Seoul, yyyy-MM-dd). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Member(Long userId, String joinedAt) {
    }
}
