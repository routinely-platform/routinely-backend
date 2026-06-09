package com.routinely.challenge_service.domain.challenge;

import com.routinely.challenge_service.domain.member.ChallengeMember;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface ChallengeQueryRepository {

    Page<Challenge> findPublicChallenges(Long userId, ChallengeSearchCondition cond, Pageable pageable);

    Page<ChallengeMember> findMyJoinedChallenges(Long userId, MyChallengeSearchCondition cond, Pageable pageable);
}
