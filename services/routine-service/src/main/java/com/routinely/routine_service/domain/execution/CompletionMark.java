package com.routinely.routine_service.domain.execution;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 인정 횟수 계산 재료 — 완료 기록 한 건의 "어느 날짜 몫인가"와 "언제 눌렀는가".
 *
 * @param scheduledDate 수행 날짜 — 주·월 캡 묶음 기준
 * @param completedAt   완료 처리 시각(서비스 시간대 벽시계) — 인정 순서와 도달 시각 기준
 */
public record CompletionMark(LocalDate scheduledDate, LocalDateTime completedAt) {
}
