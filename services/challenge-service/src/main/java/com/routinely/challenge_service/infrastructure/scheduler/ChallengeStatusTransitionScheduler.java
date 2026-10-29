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
        List<Challenge> targets = challengeRepository.findWaitingToActivate(today);
        for (Challenge challenge : targets) {
            challenge.activate();
            saveStartedOutbox(challenge);
        }
        log.info("[Scheduler] WAITING → ACTIVE 전이: {}건", targets.size());
    }

    private void endChallenges(LocalDate today) {
        List<Challenge> targets = challengeRepository.findActiveToEnd(today);
        for (Challenge challenge : targets) {
            challenge.end();
        }
        log.info("[Scheduler] ACTIVE → ENDED 전이: {}건", targets.size());
    }

    private void saveStartedOutbox(Challenge challenge) {
        String occurredAt = LocalDateTime.now(clock).atZone(clock.getZone()).toInstant().toString();
        ChallengeRoutineDefinition routine = challenge.getRoutineDefinition();
        // 시작 시점에 남아 있는 멤버만 싣는다 — 이들이 루틴을 받는다. 시작 후에는 참여할 수 없다.
        List<ChallengeMember> activeMembers = challengeMemberRepository
                .findAllByChallengeIdAndStatusOrderByJoinedAtAscIdAsc(challenge.getId(), MembershipStatus.ACTIVE);
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
                findLeaderUserId(challenge, activeMembers),
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

    private Long findLeaderUserId(Challenge challenge, List<ChallengeMember> activeMembers) {
        return activeMembers.stream()
                .filter(member -> member.getRole() == ChallengeMemberRole.LEADER)
                .map(ChallengeMember::getUserId)
                .findFirst()
                .orElseGet(() -> {
                    // 방장이 나가면 위임되거나 챌린지가 끝나므로 오지 않는 경로다. 전이 전체를 막지 않고 남긴다.
                    log.warn("[Scheduler] 시작하는 챌린지에 활성 방장이 없습니다. challengeId={}", challenge.getId());
                    return null;
                });
    }

    private String serializeEvent(Object event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new BusinessException(INTERNAL_SERVER_ERROR, "이벤트 직렬화에 실패했습니다.");
        }
    }
}
