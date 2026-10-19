package com.routinely.routine_service.application.execution;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 챌린지 루틴 백필 창 설정. (#58)
 *
 * <p>오늘 + 지난 {@code backfillDays}일까지 인증할 수 있다(경계 {@code date >= today - backfillDays}).
 * 개인 루틴에는 적용하지 않는다(수행 기간 안이면 무제한, ADR-0038).
 *
 * @param backfillDays 오늘을 뺀 지난 날 수. 기본 3 — 30일 매일 챌린지 기준 몰아치기 상한 10%
 */
@ConfigurationProperties("routinely.challenge")
public record ChallengeBackfillProperties(@DefaultValue("3") int backfillDays) {
}
