package com.routinely.challenge_service.domain.challenge;

public enum ChallengeSort {
    LATEST,   // 최신순 (createdAt DESC)
    IMMINENT, // 시작 임박순 (startedAt ASC)
    POPULAR   // 인기순 (참여 인원 많은 순)
}
