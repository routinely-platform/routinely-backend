package com.routinely.challenge_service.application;

import static com.routinely.core.exception.ErrorCode.CHALLENGE_NOT_FOUND;

import com.routinely.challenge_service.application.dto.ChallengeContextResult;
import com.routinely.challenge_service.application.dto.MembershipCheckResult;
import com.routinely.challenge_service.domain.challenge.Challenge;
import com.routinely.challenge_service.domain.challenge.ChallengeRepository;
import com.routinely.challenge_service.domain.member.ChallengeMemberRepository;
import com.routinely.challenge_service.domain.member.MembershipStatus;
import com.routinely.core.exception.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class ChallengeMembershipQueryService {

    private final ChallengeRepository challengeRepository;
    private final ChallengeMemberRepository challengeMemberRepository;

    public ChallengeMembershipQueryService(ChallengeRepository challengeRepository,
                                           ChallengeMemberRepository challengeMemberRepository) {
        this.challengeRepository = challengeRepository;
        this.challengeMemberRepository = challengeMemberRepository;
    }

    /**
     * chat-service의 채팅방 입장/메시지 발송 전 멤버 검증에 사용한다.
     * 비즈니스 판단(멤버 아님)은 예외가 아닌 응답 값으로 전달한다.
     */
    public MembershipCheckResult checkMembership(Long challengeId, Long userId) {
        return challengeMemberRepository
                .findByChallengeIdAndUserIdAndStatus(challengeId, userId, MembershipStatus.ACTIVE)
                .map(member -> MembershipCheckResult.activeMember(member.getRole()))
                .orElseGet(MembershipCheckResult::notMember);
    }

    /**
     * routine-service의 챌린지 루틴 실행 완료 처리 전 유효성 검증에 사용한다.
     */
    public ChallengeContextResult getChallengeContext(Long challengeId, Long userId) {
        Challenge challenge = challengeRepository.findById(challengeId)
                .orElseThrow(() -> new BusinessException(CHALLENGE_NOT_FOUND));

        boolean memberActive = challengeMemberRepository
                .findByChallengeIdAndUserIdAndStatus(challengeId, userId, MembershipStatus.ACTIVE)
                .isPresent();

        return new ChallengeContextResult(challenge.getStatus(), memberActive);
    }
}
