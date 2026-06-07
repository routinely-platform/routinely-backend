package com.routinely.challenge_service.infrastructure.scheduler;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.routinely.challenge_service.application.event.ChallengeStartedEvent;
import com.routinely.challenge_service.domain.challenge.Challenge;
import com.routinely.challenge_service.domain.challenge.ChallengeRepository;
import com.routinely.challenge_service.domain.outbox.ChallengeOutbox;
import com.routinely.challenge_service.domain.outbox.ChallengeOutboxRepository;
import com.routinely.challenge_service.infrastructure.kafka.KafkaTopic;
import com.routinely.core.exception.BusinessException;
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
        ChallengeStartedEvent event = new ChallengeStartedEvent(
                UUID.randomUUID().toString(),
                occurredAt,
                challenge.getId(),
                challenge.getTitle(),
                challenge.getStartedAt().toString(),
                challenge.getEndedAt().toString()
        );
        String payload = serializeEvent(event);
        String idempotencyKey = KafkaTopic.CHALLENGE_STARTED + ":" + challenge.getId();
        challengeOutboxRepository.save(
                ChallengeOutbox.create("challenge", challenge.getId(), KafkaTopic.CHALLENGE_STARTED, payload, idempotencyKey)
        );
    }

    private String serializeEvent(Object event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new BusinessException(INTERNAL_SERVER_ERROR, "이벤트 직렬화에 실패했습니다.");
        }
    }
}
