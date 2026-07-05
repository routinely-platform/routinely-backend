package com.routinely.challenge_service.domain.inbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

/**
 * Inbox 패턴 — Kafka 수신 이벤트를 저장해 중복 처리를 방지하고 처리 이력을 남긴다. (ADR-0012, ADR-0013)
 *
 * <p>Consumer는 {@link #received} 로 RECEIVED 상태로 저장만 하고 즉시 오프셋을 커밋한다.
 * 후속 비즈니스 처리(랭킹 집계 갱신)는 스케줄러가 RECEIVED 건을 폴링해 수행한다. (ADR-0014)
 * 성공 시 {@link #markProcessed}로 PROCESSED 전이하고, 실패 시 {@link #recordFailure}로
 * retry_count/last_error를 기록한 뒤 한도 초과 시에만 FAILED로 전이한다.
 */
@Entity
@Table(name = "challenge_inbox")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChallengeInbox {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "message_id", nullable = false, unique = true, length = 100)
    private String messageId;

    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private InboxStatus status;

    @Column(name = "received_at", nullable = false, updatable = false)
    private LocalDateTime receivedAt;

    @Column(name = "processed_at")
    private LocalDateTime processedAt;

    @Column(name = "aggregate_type", length = 50)
    private String aggregateType;

    @Column(name = "aggregate_id")
    private Long aggregateId;

    @Column(name = "retry_count", nullable = false)
    private int retryCount;

    @Column(name = "last_error")
    private String lastError;

    public static ChallengeInbox received(String messageId, String eventType, String payload,
                                          String aggregateType, Long aggregateId) {
        ChallengeInbox inbox = new ChallengeInbox();
        inbox.messageId = messageId;
        inbox.eventType = eventType;
        inbox.payload = payload;
        inbox.aggregateType = aggregateType;
        inbox.aggregateId = aggregateId;
        inbox.status = InboxStatus.RECEIVED;
        return inbox;
    }

    @PrePersist
    protected void onReceive() {
        if (receivedAt == null) {
            receivedAt = LocalDateTime.now();
        }
    }

    public void markProcessed(LocalDateTime now) {
        this.status = InboxStatus.PROCESSED;
        this.processedAt = now;
    }

    /**
     * 처리 실패를 기록한다. retry_count를 증가시키고 마지막 오류를 남기되,
     * MAX_RETRY를 초과하기 전까지는 {@code RECEIVED}를 유지해 스케줄러가 재시도하도록 하고,
     * 초과 시에만 {@code FAILED}(영구 실패)로 전이한다.
     *
     * @param maxRetry     최대 재시도 횟수
     * @param errorMessage 마지막 실패 원인 (호출부에서 길이 제한 적용)
     */
    public void recordFailure(int maxRetry, String errorMessage) {
        this.retryCount++;
        this.lastError = errorMessage;
        if (this.retryCount > maxRetry) {
            this.status = InboxStatus.FAILED;
        }
    }
}
