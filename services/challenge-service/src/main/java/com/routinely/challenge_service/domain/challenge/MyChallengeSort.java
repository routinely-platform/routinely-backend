package com.routinely.challenge_service.domain.challenge;

public enum MyChallengeSort {
    RECENT_JOIN,  // 최근 참여순 (joinedAt DESC)
    END_IMMINENT  // 종료일 임박순 (endedAt ASC, ENDED 챌린지 제외)
}
