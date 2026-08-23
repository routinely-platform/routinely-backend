package com.routinely.challenge_service.domain.challenge;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * 챌린지 값 제약. 규약의 단일 출처는 {@code docs/product/policies.md} §4 "최대 인원과 기간에 상한이 있는가"다.
 *
 * <p>요청 DTO(생성·수정)와 서비스 계층이 같은 값을 봐야 해서 domain에 둔다. 수정 요청은 일부 필드만
 * 올 수 있어 최종 기간을 요청만으로는 알 수 없고, 기존 엔티티 값과 병합한 뒤 서비스에서 판정한다.
 */
public final class ChallengePolicy {

    /** 최대 참여 인원 상한. 하한(2명)은 {@code ck_challenges_max_members}가 함께 강제한다. */
    public static final int MAX_MEMBERS = 20;

    /**
     * 챌린지 기간 상한. <b>시작일과 종료일을 모두 포함해</b> 센다 —
     * {@code challenge_member_summary.total_scheduled = ended_at - started_at + 1}과 같은 기준이다.
     */
    public static final int MAX_PERIOD_DAYS = 100;

    public static final String MAX_MEMBERS_MESSAGE = "최대 참여 인원은 " + MAX_MEMBERS + "명 이하여야 합니다.";
    public static final String MAX_PERIOD_MESSAGE = "챌린지 기간은 " + MAX_PERIOD_DAYS + "일 이하여야 합니다.";

    private ChallengePolicy() {
    }

    /**
     * 시작일·종료일을 모두 포함한 일수가 상한 이내인지 판정한다.
     * 종료일이 시작일보다 빠른 경우는 별도 제약이 처리하므로 여기서는 판단하지 않는다.
     */
    public static boolean isPeriodWithinLimit(LocalDate startedAt, LocalDate endedAt) {
        return ChronoUnit.DAYS.between(startedAt, endedAt) + 1 <= MAX_PERIOD_DAYS;
    }
}
