package com.routinely.routine_service.domain.inbox;

/**
 * Inbox 메시지 처리 상태.
 * <ul>
 *     <li>{@code RECEIVED} — 처리 대기/재시도 대기 상태 (스케줄러 폴링 대상).
 *         처리 실패해도 MAX_RETRY 이내라면 retry_count만 증가하고 이 상태를 유지해 재시도된다.</li>
 *     <li>{@code PROCESSED} — 후속 비즈니스 처리(템플릿 생성)까지 완료</li>
 *     <li>{@code FAILED} — MAX_RETRY를 초과한 영구 실패 (스케줄러 재폴링 대상에서 제외, 운영 수동 복구 대상)</li>
 * </ul>
 */
public enum InboxStatus {
    RECEIVED, PROCESSED, FAILED
}
