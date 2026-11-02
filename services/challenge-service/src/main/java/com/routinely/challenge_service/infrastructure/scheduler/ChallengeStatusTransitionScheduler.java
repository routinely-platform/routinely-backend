package com.routinely.challenge_service.infrastructure.scheduler;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.routinely.challenge_service.application.event.ChallengeStartedEvent;
import com.routinely.challenge_service.domain.challenge.Challenge;
import com.routinely.challenge_service.domain.challenge.ChallengeRepository;
import com.routinely.challenge_service.domain.challenge.ChallengeRoutineDefinition;
import com.routinely.challenge_service.domain.member.ChallengeMember;
import com.routinely.challenge_service.domain.member.ChallengeMemberRepository;
import com.routinely.challenge_service.domain.member.ChallengeMemberRole;
import com.routinely.challenge_service.domain.member.MembershipStatus;
import com.routinely.challenge_service.domain.outbox.ChallengeOutbox;
import com.routinely.challenge_service.domain.outbox.ChallengeOutboxRepository;
import com.routinely.challenge_service.infrastructure.kafka.KafkaTopic;
import com.routinely.core.exception.BusinessException;
import com.routinely.core.schedule.Weekdays;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.routinely.core.exception.ErrorCode.INTERNAL_SERVER_ERROR;

@Slf4j
@Component
@RequiredArgsConstructor
public class ChallengeStatusTransitionScheduler {

    private final ChallengeRepository challengeRepository;
    private final ChallengeMemberRepository challengeMemberRepository;
    private final ChallengeOutboxRepository challengeOutboxRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Scheduled(cron = "0 0 0 * * *", zone = "Asia/Seoul")
    @SchedulerLock(
            name = "challenge-status-transition",
            lockAtMostFor = "5m",
            lockAtLeastFor = "1m"
    )
    @Transactional
    public void transitionChallengeStatus() {
        LocalDate today = LocalDate.now(clock);
        activateChallenges(today);
        endChallenges(today);
    }

    private void activateChallenges(LocalDate today) {
        // 대상 행을 잠가 참여 · 탈퇴 · 수정과 줄 세운다 — 멤버 스냅샷을 읽는 동안 구성원이 바뀌지 않는다.
        List<Challenge> targets = challengeRepository.findWaitingToActivate(today);
        int activated = 0;
        for (Challenge challenge : targets) {
            // 시작 시점에 남아 있는 멤버만 싣는다 — 이들이 루틴을 받는다. 시작 후에는 참여할 수 없다.
            List<ChallengeMember> activeMembers = challengeMemberRepository
                    .findAllByChallengeIdAndStatusOrderByJoinedAtAscIdAsc(challenge.getId(), MembershipStatus.ACTIVE);
            Optional<Long> leaderUserId = findLeaderUserId(activeMembers);
            if (leaderUserId.isEmpty()) {
                // leaderUserId는 필수 필드다. 예외를 던지면 같은 트랜잭션의 다른 챌린지까지 롤백되므로 이 챌린지만 건너뛴다.
                log.error("[Scheduler] 활성 방장이 없어 챌린지 시작을 건너뜁니다. challengeId={}", challenge.getId());
                continue;
            }
            challenge.activate();
            saveStartedOutbox(challenge, leaderUserId.get(), activeMembers);
            activated++;
        }
        log.info("[Scheduler] WAITING → ACTIVE 전이: {}건, 방장이 없어 건너뜀: {}건", activated, targets.size() - activated);
    }

    private void endChallenges(LocalDate today) {
        List<Challenge> targets = challengeRepository.findActiveToEnd(today);
        for (Challenge challenge : targets) {
            challenge.end();
        }
        log.info("[Scheduler] ACTIVE → ENDED 전이: {}건", targets.size());
    }

    private void saveStartedOutbox(Challenge challenge, Long leaderUserId, List<ChallengeMember> activeMembers) {
        String occurredAt = LocalDateTime.now(clock).atZone(clock.getZone()).toInstant().toString();
        ChallengeRoutineDefinition routine = challenge.getRoutineDefinition();
        ChallengeStartedEvent event = new ChallengeStartedEvent(
                UUID.randomUUID().toString(),
                occurredAt,
                challenge.getId(),
                challenge.getTitle(),
                challenge.getStartedAt().toString(),
                challenge.getEndedAt().toString(),
                routine.getRoutineTitle(),
                challenge.getCategoryCode(),
                routine.getScheduleType().name(),
                routine.getDaysOfWeek() == null ? null : Weekdays.toCodes(routine.getDaysOfWeek()),
                routine.getTargetCount(),
                leaderUserId,
                activeMembers.stream()
                        .map(member -> new ChallengeStartedEvent.Member(
                                member.getUserId(),
                                member.getJoinedAt().toLocalDate().toString()))
                        .toList()
        );
        String payload = serializeEvent(event);
        String idempotencyKey = KafkaTopic.CHALLENGE_STARTED + ":" + challenge.getId();
        challengeOutboxRepository.save(
                ChallengeOutbox.create("challenge", challenge.getId(), KafkaTopic.CHALLENGE_STARTED, payload, idempotencyKey)
        );
    }

    private Optional<Long> findLeaderUserId(List<ChallengeMember> activeMembers) {
        return activeMembers.stream()
                .filter(member -> member.getRole() == ChallengeMemberRole.LEADER)
                .map(ChallengeMember::getUserId)
                .findFirst();
    }

    private String serializeEvent(Object event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new BusinessException(INTERNAL_SERVER_ERROR, "이벤트 직렬화에 실패했습니다.");
        }
    }
}
