package com.routinely.challenge_service.application;

import com.routinely.challenge_service.application.dto.ChallengeListResult;
import com.routinely.challenge_service.application.dto.ChallengeResult;
import com.routinely.challenge_service.application.dto.CreateChallengeCommand;
import com.routinely.challenge_service.application.dto.UpdateChallengeCommand;
import com.routinely.challenge_service.domain.Challenge;
import com.routinely.challenge_service.domain.ChallengeLifecycleStatus;
import com.routinely.challenge_service.domain.ChallengeMember;
import com.routinely.challenge_service.domain.ChallengeMemberRole;
import com.routinely.challenge_service.domain.ChallengeMemberRepository;
import com.routinely.challenge_service.domain.ChallengeRepository;
import com.routinely.challenge_service.domain.MemberCountProjection;
import com.routinely.challenge_service.domain.MembershipStatus;
import com.routinely.core.exception.BusinessException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.routinely.core.exception.ErrorCode.CHALLENGE_NOT_FOUND;
import static com.routinely.core.exception.ErrorCode.FORBIDDEN;
import static com.routinely.core.exception.ErrorCode.NOT_CHALLENGE_MEMBER;
import static com.routinely.core.exception.ErrorCode.VALIDATION_FAILED;

@Service
@Transactional(readOnly = true)
public class ChallengeService {

    private final ChallengeRepository challengeRepository;
    private final ChallengeMemberRepository challengeMemberRepository;
    private final Clock clock;

    public ChallengeService(ChallengeRepository challengeRepository,
                            ChallengeMemberRepository challengeMemberRepository,
                            Clock clock) {
        this.challengeRepository = challengeRepository;
        this.challengeMemberRepository = challengeMemberRepository;
        this.clock = clock;
    }

    @Transactional
    public ChallengeResult createChallenge(Long creatorUserId, CreateChallengeCommand command) {
        String inviteCode = command.isPublic() ? null : generateInviteCode();

        Challenge challenge = Challenge.create(command, creatorUserId, inviteCode);
        Challenge savedChallenge = challengeRepository.save(challenge);

        ChallengeMember leader = ChallengeMember.createLeader(savedChallenge, creatorUserId, now());
        challengeMemberRepository.save(leader);

        // TODO: #48 - ChallengeCreatedEvent Outbox 저장
        //   - routine-service: 챌린지 연결 루틴 템플릿 생성
        //   - notification-service: 초대 대상자에게 초대 알림 발송

        return ChallengeResult.from(
                savedChallenge,
                1,
                savedChallenge.getInviteCode(),
                ChallengeMemberRole.LEADER
        );
    }

    @Transactional
    public ChallengeResult updateChallenge(Long challengeId, Long requestUserId, UpdateChallengeCommand command) {
        Challenge challenge = findChallengeByIdOrThrow(challengeId);
        validateLeader(challengeId, requestUserId);
        validateChallengeIsWaiting(challenge);
        validateRoutineUpdateNotSupported(command);

        int currentMemberCount = challengeMemberRepository
                .countByChallengeIdAndStatus(challengeId, MembershipStatus.ACTIVE);

        if (command.title() != null) {
            challenge.updateTitle(command.title());
        }

        if (command.description() != null) {
            challenge.updateDescription(command.description());
        }

        if (command.isPublic() != null) {
            boolean requestedIsPublic = command.isPublic();
            validatePublicTransition(challenge, requestedIsPublic);

            if (!challenge.isPublic() && requestedIsPublic) {
                challenge.makePublic();
            }
        }

        if (command.maxMembers() != null) {
            validateMaxMembers(command.maxMembers(), currentMemberCount);
            challenge.updateMaxMembers(command.maxMembers());
        }

        if (command.startedAt() != null && command.startedAt().isBefore(LocalDate.now(clock))) {
            throw new BusinessException(VALIDATION_FAILED, "시작일은 오늘 이후여야 합니다.");
        }

        LocalDate targetStartedAt = command.startedAt() != null ? command.startedAt() : challenge.getStartedAt();
        LocalDate targetEndedAt = command.endedAt() != null ? command.endedAt() : challenge.getEndedAt();

        validateScheduleChange(targetStartedAt, targetEndedAt);

        if (command.startedAt() != null) {
            challenge.updateStartedAt(command.startedAt());
        }

        if (command.endedAt() != null) {
            challenge.updateEndedAt(command.endedAt());
        }

        // TODO: routineTitle, routinePreferredTime 수정은 routine-service grpc 통신을 통한 수정

        return ChallengeResult.from(
                challenge,
                currentMemberCount,
                challenge.isPublic() ? null : challenge.getInviteCode(),
                ChallengeMemberRole.LEADER
        );
    }

    public ChallengeListResult getMyJoinedChallenges(Long userId, Pageable pageable) {
        Page<ChallengeMember> page = challengeMemberRepository.findByUserIdAndStatus(
                userId,
                MembershipStatus.ACTIVE,
                pageable
        );
        return buildJoinedChallengeListResult(page);
    }

    // TODO: #120 - keyword 검색, categoryCode 필터, 정렬 옵션 (Querydsl 동적 쿼리로 구현)
    public ChallengeListResult getPublicChallenges(Long userId, Pageable pageable) {
        Page<Challenge> page = challengeRepository.findJoinablePublicChallenges(
                userId,
                ChallengeLifecycleStatus.WAITING,
                EnumSet.of(MembershipStatus.ACTIVE, MembershipStatus.EXPELLED),
                pageable
        );
        return buildChallengeListResult(page);
    }

    private ChallengeListResult buildChallengeListResult(Page<Challenge> page) {
        Map<Long, Integer> memberCounts = countActiveMembersByChallengeIds(
                page.getContent().stream()
                        .map(Challenge::getId)
                        .toList()
        );

        return ChallengeListResult.from(page, memberCounts);
    }

    private ChallengeListResult buildJoinedChallengeListResult(Page<ChallengeMember> page) {
        Map<Long, Integer> memberCounts = countActiveMembersByChallengeIds(
                page.getContent().stream()
                        .map(member -> member.getChallenge().getId())
                        .toList()
        );

        return ChallengeListResult.fromMemberships(page, memberCounts);
    }

    private Map<Long, Integer> countActiveMembersByChallengeIds(List<Long> challengeIds) {
        if (challengeIds.isEmpty()) {
            return Map.of();
        }

        return challengeMemberRepository
                .countMembersByChallengeIdsAndStatus(challengeIds, MembershipStatus.ACTIVE)
                .stream()
                .collect(Collectors.toMap(
                        MemberCountProjection::getChallengeId,
                        p -> p.getCount().intValue()));
    }

    public ChallengeResult getChallengeDetail(Long challengeId, Long requestUserId) {
        Challenge challenge = findChallengeByIdOrThrow(challengeId);
        ChallengeMember activeMember = findActiveMembership(challengeId, requestUserId)
                .orElseThrow(() -> new BusinessException(NOT_CHALLENGE_MEMBER));
        int memberCount = challengeMemberRepository
                .countByChallengeIdAndStatus(challengeId, MembershipStatus.ACTIVE);

        // TODO: routine_template, routines, routine_executions, challenge_member_summary, feed, member 정보 조회 필
        // routine-service(routine_template, routines, routine_executions, feeds)에는 grpc 통신, member-srevice(member)에도 grpc 통신 (공통)
        // CompletableFuture로 병렬 처리
        // 리더인 경우에만 챌린지 참여자 목록 조회할지 고려 필요

        return ChallengeResult.from(
                challenge,
                memberCount,
                resolveVisibleInviteCode(challenge, activeMember),
                activeMember.getRole()
        );
    }

    // TODO: #45 - joinChallenge(Long challengeId, Long userId): 공개 챌린지 참여
    // TODO: #45 - joinByInviteCode(String inviteCode, Long userId): 초대 코드로 비공개 챌린지 참여
    // TODO: #45 - leaveChallenge(Long challengeId, Long userId): 탈퇴 (LEADER 탈퇴 시 위임 or 챌린지 종료 정책 결정 필요)
    // TODO: #45 - kickMember(Long challengeId, Long requestUserId, Long targetUserId): LEADER가 멤버 강제 퇴장

    private String generateInviteCode() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 20);
    }

    private Challenge findChallengeByIdOrThrow(Long challengeId) {
        return challengeRepository.findById(challengeId)
                .orElseThrow(() -> new BusinessException(CHALLENGE_NOT_FOUND));
    }

    private Optional<ChallengeMember> findActiveMembership(Long challengeId, Long userId) {
        return challengeMemberRepository.findByChallengeIdAndUserIdAndStatus(
                challengeId,
                userId,
                MembershipStatus.ACTIVE
        );
    }

    private void validateLeader(Long challengeId, Long requestUserId) {
        ChallengeMember member = findActiveMembership(challengeId, requestUserId)
                .orElseThrow(() -> new BusinessException(NOT_CHALLENGE_MEMBER));

        if (member.getRole() != ChallengeMemberRole.LEADER) {
            throw new BusinessException(FORBIDDEN, "챌린지 방장만 수정할 수 있습니다.");
        }
    }

    private void validateChallengeIsWaiting(Challenge challenge) {
        if (challenge.getStatus() != ChallengeLifecycleStatus.WAITING) {
            throw new BusinessException(VALIDATION_FAILED, "대기 중인 챌린지만 수정할 수 있습니다.");
        }
    }

    private void validateRoutineUpdateNotSupported(UpdateChallengeCommand command) {
        if (command.routineTitle() != null || command.routinePreferredTime() != null) {
            // TODO: routine-service 개발 이후 gRPC 통신으로 루틴 템플릿 수정 처리
            throw new BusinessException(VALIDATION_FAILED, "루틴 정보 수정은 아직 지원하지 않습니다.");
        }
    }

    private void validatePublicTransition(Challenge challenge, boolean requestedIsPublic) {
        if (challenge.isPublic() && !requestedIsPublic) {
            throw new BusinessException(VALIDATION_FAILED, "공개 챌린지는 비공개로 변경할 수 없습니다.");
        }
    }

    private void validateMaxMembers(int requestedMaxMembers, int currentMemberCount) {
        if (requestedMaxMembers < currentMemberCount) {
            throw new BusinessException(
                    VALIDATION_FAILED,
                    "최대 참여 인원은 현재 참여 중인 멤버 수보다 작을 수 없습니다."
            );
        }
    }

    private void validateScheduleChange(LocalDate targetStartedAt, LocalDate targetEndedAt) {
        if (targetEndedAt.isBefore(targetStartedAt)) {
            throw new BusinessException(VALIDATION_FAILED, "종료일은 시작일보다 빠를 수 없습니다.");
        }
    }

    private String resolveVisibleInviteCode(Challenge challenge, ChallengeMember activeMember) {
        if (challenge.isPublic()) {
            return null;
        }

        return activeMember.getRole() == ChallengeMemberRole.LEADER ? challenge.getInviteCode() : null;
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }
}
