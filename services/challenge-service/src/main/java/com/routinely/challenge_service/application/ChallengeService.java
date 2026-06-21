package com.routinely.challenge_service.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.routinely.challenge_service.application.dto.ChallengeListResult;
import com.routinely.challenge_service.application.dto.ChallengeMemberResult;
import com.routinely.challenge_service.application.dto.ChallengeResult;
import com.routinely.challenge_service.application.dto.CreateChallengeCommand;
import com.routinely.challenge_service.application.dto.UpdateChallengeCommand;
import com.routinely.challenge_service.application.event.ChallengeCreatedEvent;
import com.routinely.challenge_service.application.event.ChallengeMemberJoinedEvent;
import com.routinely.challenge_service.application.event.ChallengeMemberLeftEvent;
import com.routinely.challenge_service.domain.challenge.Challenge;
import com.routinely.challenge_service.domain.challenge.ChallengeLifecycleStatus;
import com.routinely.challenge_service.domain.challenge.ChallengeQueryRepository;
import com.routinely.challenge_service.domain.challenge.ChallengeRepository;
import com.routinely.challenge_service.domain.challenge.ChallengeSearchCondition;
import com.routinely.challenge_service.domain.challenge.MyChallengeSearchCondition;
import com.routinely.challenge_service.domain.member.ChallengeMember;
import com.routinely.challenge_service.domain.member.ChallengeMemberRepository;
import com.routinely.challenge_service.domain.member.ChallengeMemberRole;
import com.routinely.challenge_service.domain.member.MemberCountProjection;
import com.routinely.challenge_service.domain.member.MembershipStatus;
import com.routinely.challenge_service.domain.outbox.ChallengeOutbox;
import com.routinely.challenge_service.domain.outbox.ChallengeOutboxRepository;
import com.routinely.challenge_service.infrastructure.kafka.KafkaTopic;
import com.routinely.core.exception.BusinessException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.routinely.core.exception.ErrorCode.CHALLENGE_ALREADY_ACTIVE;
import static com.routinely.core.exception.ErrorCode.CHALLENGE_ALREADY_JOINED;
import static com.routinely.core.exception.ErrorCode.CHALLENGE_FULL;
import static com.routinely.core.exception.ErrorCode.CHALLENGE_MEMBER_EXPELLED;
import static com.routinely.core.exception.ErrorCode.CHALLENGE_NOT_FOUND;
import static com.routinely.core.exception.ErrorCode.FORBIDDEN;
import static com.routinely.core.exception.ErrorCode.INTERNAL_SERVER_ERROR;
import static com.routinely.core.exception.ErrorCode.NOT_CHALLENGE_MEMBER;
import static com.routinely.core.exception.ErrorCode.VALIDATION_FAILED;

@Service
@Transactional(readOnly = true)
public class ChallengeService {

    private static final String AGGREGATE_TYPE = "challenge";

    private final ChallengeRepository challengeRepository;
    private final ChallengeQueryRepository challengeQueryRepository;
    private final ChallengeMemberRepository challengeMemberRepository;
    private final ChallengeOutboxRepository challengeOutboxRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public ChallengeService(ChallengeRepository challengeRepository,
                            ChallengeQueryRepository challengeQueryRepository,
                            ChallengeMemberRepository challengeMemberRepository,
                            ChallengeOutboxRepository challengeOutboxRepository,
                            ObjectMapper objectMapper,
                            Clock clock) {
        this.challengeRepository = challengeRepository;
        this.challengeQueryRepository = challengeQueryRepository;
        this.challengeMemberRepository = challengeMemberRepository;
        this.challengeOutboxRepository = challengeOutboxRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional
    public ChallengeResult createChallengeAfterCategoryValidation(Long creatorUserId, CreateChallengeCommand command) {
        String inviteCode = command.isPublic() ? null : generateInviteCode();

        Challenge challenge = Challenge.create(command, creatorUserId, inviteCode);
        Challenge savedChallenge = challengeRepository.save(challenge);

        ChallengeMember leader = ChallengeMember.createLeader(savedChallenge, creatorUserId, now());
        challengeMemberRepository.save(leader);

        saveCreatedOutbox(savedChallenge, creatorUserId, command, toEventOccurredAt(now()));

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
        validateRoutineUpdateNotSupported(command);

        boolean hasNonVisibilityChange = hasNonVisibilityFields(command);
        if (hasNonVisibilityChange) {
            validateChallengeIsWaiting(challenge);
        }

        int currentMemberCount = challengeMemberRepository
                .countByChallengeIdAndStatus(challengeId, MembershipStatus.ACTIVE);
        validateActiveMemberCount(currentMemberCount);

        if (command.isPublic() != null) {
            validateVisibilityUpdate(challenge, command.isPublic(), currentMemberCount);
        }

        if (hasNonVisibilityChange) {
            if (currentMemberCount == 1) {
                validateFreeNonVisibilityUpdate(challenge, command);
            } else {
                validateRestrictedNonVisibilityUpdate(challenge, command);
            }
        }

        if (command.isPublic() != null) {
            applyVisibilityUpdate(challenge, command.isPublic());
        }

        if (hasNonVisibilityChange) {
            applyNonVisibilityUpdate(challenge, command);
        }

        return ChallengeResult.from(
                challenge,
                currentMemberCount,
                challenge.isPublic() ? null : challenge.getInviteCode(),
                ChallengeMemberRole.LEADER
        );
    }

    public ChallengeListResult getMyJoinedChallenges(Long userId, MyChallengeSearchCondition cond, Pageable pageable) {
        validateMyChallengeSearchCondition(cond);
        Page<ChallengeMember> page = challengeQueryRepository.findMyJoinedChallenges(userId, cond, pageable);
        return buildJoinedChallengeListResult(page);
    }

    public ChallengeListResult getPublicChallenges(Long userId, ChallengeSearchCondition cond, Pageable pageable) {
        Page<Challenge> page = challengeQueryRepository.findPublicChallenges(userId, cond, pageable);
        return buildChallengeListResult(page);
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

    @Transactional
    public ChallengeMemberResult joinChallenge(Long challengeId, Long userId) {
        Challenge challenge = findChallengeByIdForUpdateOrThrow(challengeId);

        if (!challenge.isPublic()) {
            throw new BusinessException(FORBIDDEN, "공개 챌린지에만 참여할 수 있습니다.");
        }
        if (challenge.getStatus() != ChallengeLifecycleStatus.WAITING) {
            throw new BusinessException(CHALLENGE_ALREADY_ACTIVE);
        }

        Optional<ChallengeMember> existingMember =
                challengeMemberRepository.findByChallengeIdAndUserId(challengeId, userId);

        if (existingMember.isPresent()) {
            ChallengeMember member = existingMember.get();
            if (member.isActive()) {
                throw new BusinessException(CHALLENGE_ALREADY_JOINED);
            }
            if (member.isExpelled()) {
                throw new BusinessException(CHALLENGE_MEMBER_EXPELLED);
            }

            validateCapacity(challengeId, challenge.getMaxMembers());
            LocalDateTime joinedAt = now();
            member.rejoin(joinedAt);
            saveJoinedOutbox(challenge, userId, member.getRole(), toEventOccurredAt(joinedAt));
            return ChallengeMemberResult.from(member);
        }

        validateCapacity(challengeId, challenge.getMaxMembers());
        LocalDateTime joinedAt = now();
        ChallengeMember member = ChallengeMember.createMember(challenge, userId, joinedAt);
        ChallengeMember savedMember = challengeMemberRepository.save(member);
        saveJoinedOutbox(challenge, userId, savedMember.getRole(), toEventOccurredAt(joinedAt));
        return ChallengeMemberResult.from(savedMember);
    }

    @Transactional
    public void leaveChallenge(Long challengeId, Long userId) {
        Challenge challenge = findChallengeByIdOrThrow(challengeId);

        ChallengeMember member = challengeMemberRepository
                .findByChallengeIdAndUserIdAndStatus(challengeId, userId, MembershipStatus.ACTIVE)
                .orElseThrow(() -> new BusinessException(NOT_CHALLENGE_MEMBER));

        if (member.getRole() == ChallengeMemberRole.LEADER) {
            Optional<ChallengeMember> nextLeader = challengeMemberRepository
                    .findFirstByChallengeIdAndStatusAndRoleNotOrderByJoinedAtAsc(
                            challengeId, MembershipStatus.ACTIVE, ChallengeMemberRole.LEADER);

            if (nextLeader.isPresent()) {
                nextLeader.get().promoteToLeader();
                member.demoteToMember();
            } else {
                challenge.end();
            }
        }

        LocalDateTime leftAt = now();
        member.leave(leftAt);
        saveLeftOutbox(challengeId, userId, "LEFT", toEventOccurredAt(leftAt));
    }

    // TODO: v2 - joinByInviteCode(String inviteCode, Long userId): 초대 코드로 비공개 챌린지 참여

    private void validateCapacity(Long challengeId, int maxMembers) {
        int activeCount = challengeMemberRepository.countByChallengeIdAndStatus(challengeId, MembershipStatus.ACTIVE);
        if (activeCount >= maxMembers) {
            throw new BusinessException(CHALLENGE_FULL);
        }
    }

    private void validateMyChallengeSearchCondition(MyChallengeSearchCondition cond) {
        if (cond.hasEndedStatusWithEndImminentSort()) {
            throw new BusinessException(VALIDATION_FAILED, "종료 임박순은 종료된 챌린지와 함께 조회할 수 없습니다.");
        }
    }

    private void saveCreatedOutbox(Challenge challenge, Long creatorUserId, CreateChallengeCommand command, String occurredAt) {
        // routine-service가 추가 RPC 없이 루틴 템플릿을 생성할 수 있도록 self-contained payload를 구성한다.
        // 루틴 필드(routineTitle/repeatType/repeatValue)는 challenge-service가 저장하지 않고 그대로 전달한다.
        // 선호 시간은 챌린지 템플릿이 정하지 않으며, 멤버별 routine 인스턴스에서 개별 설정한다 (ADR-0035).
        ChallengeCreatedEvent event = new ChallengeCreatedEvent(
                UUID.randomUUID().toString(),
                occurredAt,
                challenge.getId(),
                creatorUserId,
                challenge.getCategoryCode(),
                command.routineTitle(),
                command.repeatType(),
                command.repeatValue(),
                challenge.getStartedAt().toString(),
                challenge.getEndedAt().toString()
        );
        String payload = serializeEvent(event);
        // 챌린지는 한 번만 생성되므로 challengeId만으로 유일성을 보장한다.
        String idempotencyKey = KafkaTopic.CHALLENGE_CREATED + ":" + challenge.getId();
        challengeOutboxRepository.save(
                ChallengeOutbox.create(AGGREGATE_TYPE, challenge.getId(), KafkaTopic.CHALLENGE_CREATED, payload, idempotencyKey)
        );
    }

    private void saveJoinedOutbox(Challenge challenge, Long userId, ChallengeMemberRole role, String occurredAt) {
        ChallengeMemberJoinedEvent event = new ChallengeMemberJoinedEvent(
                UUID.randomUUID().toString(),
                occurredAt,
                challenge.getId(),
                challenge.getTitle(),
                userId,
                role.name()
        );
        String payload = serializeEvent(event);
        String idempotencyKey = KafkaTopic.CHALLENGE_MEMBER_JOINED + ":" + challenge.getId() + ":" + userId + ":" + occurredAt;
        challengeOutboxRepository.save(
                ChallengeOutbox.create(AGGREGATE_TYPE, challenge.getId(), KafkaTopic.CHALLENGE_MEMBER_JOINED, payload, idempotencyKey)
        );
    }

    private void saveLeftOutbox(Long challengeId, Long userId, String reason, String occurredAt) {
        ChallengeMemberLeftEvent event = new ChallengeMemberLeftEvent(
                UUID.randomUUID().toString(),
                occurredAt,
                challengeId,
                userId,
                reason
        );
        String payload = serializeEvent(event);
        String idempotencyKey = KafkaTopic.CHALLENGE_MEMBER_LEFT + ":" + challengeId + ":" + userId + ":" + occurredAt;
        challengeOutboxRepository.save(
                ChallengeOutbox.create(AGGREGATE_TYPE, challengeId, KafkaTopic.CHALLENGE_MEMBER_LEFT, payload, idempotencyKey)
        );
    }

    private String serializeEvent(Object event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new BusinessException(INTERNAL_SERVER_ERROR, "이벤트 직렬화에 실패했습니다.");
        }
    }

    private String generateInviteCode() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 20);
    }

    private Challenge findChallengeByIdOrThrow(Long challengeId) {
        return challengeRepository.findById(challengeId)
                .orElseThrow(() -> new BusinessException(CHALLENGE_NOT_FOUND));
    }

    private Challenge findChallengeByIdForUpdateOrThrow(Long challengeId) {
        return challengeRepository.findByIdForUpdate(challengeId)
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

    private boolean hasNonVisibilityFields(UpdateChallengeCommand command) {
        return command.title() != null
                || command.description() != null
                || command.maxMembers() != null
                || command.startedAt() != null
                || command.endedAt() != null;
    }

    private void validateActiveMemberCount(int memberCount) {
        if (memberCount < 1) {
            throw new BusinessException(INTERNAL_SERVER_ERROR, "챌린지 멤버 상태가 올바르지 않습니다.");
        }
    }

    private void validateVisibilityUpdate(Challenge challenge, boolean requestedIsPublic, int memberCount) {
        if (challenge.getStatus() == ChallengeLifecycleStatus.ENDED) {
            throw new BusinessException(VALIDATION_FAILED, "종료된 챌린지는 공개 여부를 수정할 수 없습니다.");
        }

        if (challenge.isPublic() == requestedIsPublic) {
            return;
        }

        if (challenge.isPublic() && !requestedIsPublic && memberCount > 1) {
            throw new BusinessException(VALIDATION_FAILED, "멤버가 존재하는 챌린지는 공개 → 비공개 변경이 불가능합니다.");
        }
    }

    private void applyVisibilityUpdate(Challenge challenge, boolean requestedIsPublic) {
        if (challenge.isPublic() == requestedIsPublic) {
            return;
        }

        if (requestedIsPublic) {
            challenge.makePublic();
        } else {
            String inviteCode = challenge.getInviteCode() != null ? challenge.getInviteCode() : generateInviteCode();
            challenge.makePrivate(inviteCode);
        }
    }

    private void validateFreeNonVisibilityUpdate(Challenge challenge, UpdateChallengeCommand command) {
        if (command.startedAt() != null && command.startedAt().isBefore(LocalDate.now(clock))) {
            throw new BusinessException(VALIDATION_FAILED, "시작일은 오늘 이후여야 합니다.");
        }
        LocalDate targetStartedAt = command.startedAt() != null ? command.startedAt() : challenge.getStartedAt();
        LocalDate targetEndedAt = command.endedAt() != null ? command.endedAt() : challenge.getEndedAt();
        validateScheduleChange(targetStartedAt, targetEndedAt);
    }

    private void validateRestrictedNonVisibilityUpdate(Challenge challenge, UpdateChallengeCommand command) {
        if (command.title() != null) {
            throw new BusinessException(VALIDATION_FAILED, "다른 멤버가 있어 제목을 변경할 수 없습니다.");
        }
        if (command.startedAt() != null) {
            throw new BusinessException(VALIDATION_FAILED, "다른 멤버가 있어 시작일을 변경할 수 없습니다.");
        }
        if (command.endedAt() != null) {
            throw new BusinessException(VALIDATION_FAILED, "다른 멤버가 있어 종료일을 변경할 수 없습니다.");
        }
        if (command.maxMembers() != null && command.maxMembers() < challenge.getMaxMembers()) {
            throw new BusinessException(VALIDATION_FAILED, "다른 멤버가 있어 최대 인원을 줄일 수 없습니다.");
        }
    }

    private void applyNonVisibilityUpdate(Challenge challenge, UpdateChallengeCommand command) {
        if (command.title() != null) {
            challenge.updateTitle(command.title());
        }
        if (command.description() != null) {
            challenge.updateDescription(command.description());
        }
        if (command.maxMembers() != null) {
            challenge.updateMaxMembers(command.maxMembers());
        }
        if (command.startedAt() != null) {
            challenge.updateStartedAt(command.startedAt());
        }
        if (command.endedAt() != null) {
            challenge.updateEndedAt(command.endedAt());
        }
    }

    private void validateScheduleChange(LocalDate targetStartedAt, LocalDate targetEndedAt) {
        if (targetEndedAt.isBefore(targetStartedAt)) {
            throw new BusinessException(VALIDATION_FAILED, "종료일은 시작일보다 빠를 수 없습니다.");
        }
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

    private String resolveVisibleInviteCode(Challenge challenge, ChallengeMember activeMember) {
        if (challenge.isPublic()) {
            return null;
        }

        return activeMember.getRole() == ChallengeMemberRole.LEADER ? challenge.getInviteCode() : null;
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    private String toEventOccurredAt(LocalDateTime dateTime) {
        return dateTime.atZone(clock.getZone()).toInstant().toString();
    }
}
