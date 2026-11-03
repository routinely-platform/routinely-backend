package com.routinely.challenge_service.application;

import com.routinely.challenge_service.domain.challenge.Challenge;
import com.routinely.challenge_service.domain.challenge.ChallengeImage;
import com.routinely.challenge_service.domain.challenge.ChallengeRepository;
import com.routinely.challenge_service.domain.member.ChallengeMember;
import com.routinely.challenge_service.domain.member.ChallengeMemberRepository;
import com.routinely.challenge_service.domain.member.ChallengeMemberRole;
import com.routinely.challenge_service.domain.member.MembershipStatus;
import com.routinely.core.exception.BusinessException;
import com.routinely.core.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 챌린지 대표 이미지의 DB 반영. {@link ChallengeImageService}가 S3 전송을 트랜잭션 밖에서 마친 뒤 부른다.
 *
 * <p>챌린지 행을 잠그고 쓴다 — 일반 조회로 읽으면 커밋 때 전체 컬럼 UPDATE가 그사이 바뀐
 * {@code status}·{@code routine_title}을 되돌린다. 쓰기 메서드가 반환되면 커밋이 끝난 것이다.
 */
@Component
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ChallengeImageWriter {

    private final ChallengeRepository challengeRepository;
    private final ChallengeMemberRepository challengeMemberRepository;

    /** S3 전송 전 사전 확인. 락을 잡지 않으므로 {@link #changeImage}가 잠근 뒤 다시 확인한다. */
    public void checkLeader(Long challengeId, Long requesterUserId) {
        if (!challengeRepository.existsById(challengeId)) {
            throw new BusinessException(ErrorCode.CHALLENGE_NOT_FOUND);
        }
        validateLeader(challengeId, requesterUserId);
    }

    /** 새 이미지를 반영하고, 커밋 후 지울 이전 오브젝트 키를 돌려준다. 이전 이미지가 없으면 {@code null}. */
    @Transactional
    public String changeImage(Long challengeId, Long requesterUserId, ChallengeImage image) {
        Challenge challenge = findChallengeByIdForUpdateOrThrow(challengeId);
        validateLeader(challengeId, requesterUserId);

        String previousObjectKey = challenge.getImageObjectKey();
        challenge.changeImage(image);
        return previousObjectKey;
    }

    /** 이미지 참조를 지우고, 커밋 후 지울 오브젝트 키를 돌려준다. 이미지가 없으면 {@code null}. */
    @Transactional
    public String removeImage(Long challengeId, Long requesterUserId) {
        Challenge challenge = findChallengeByIdForUpdateOrThrow(challengeId);
        validateLeader(challengeId, requesterUserId);

        String objectKey = challenge.getImageObjectKey();
        if (objectKey != null) {
            challenge.removeImage();
        }
        return objectKey;
    }

    private Challenge findChallengeByIdForUpdateOrThrow(Long challengeId) {
        return challengeRepository.findByIdForUpdate(challengeId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CHALLENGE_NOT_FOUND));
    }

    private void validateLeader(Long challengeId, Long requesterUserId) {
        ChallengeMember member = challengeMemberRepository
                .findByChallengeIdAndUserIdAndStatus(challengeId, requesterUserId, MembershipStatus.ACTIVE)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_CHALLENGE_MEMBER));

        if (member.getRole() != ChallengeMemberRole.LEADER) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "챌린지 방장만 대표 이미지를 변경할 수 있습니다.");
        }
    }
}
