package com.routinely.challenge_service.domain.outbox;

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

@Entity
@Table(name = "challenge_outbox")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChallengeOutbox {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "aggregate_type", nullable = false, length = 50)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false)
    private Long aggregateId;

    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OutboxStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "published_at")
    private LocalDateTime publishedAt;

    @Column(name = "retry_count", nullable = false)
    private int retryCount;

    @Column(name = "idempotency_key", unique = true, length = 200)
    private String idempotencyKey;

    public static ChallengeOutbox create(String aggregateType, Long aggregateId,
                                         String eventType, String payload,
                                         String idempotencyKey) {
        ChallengeOutbox outbox = new ChallengeOutbox();
        outbox.aggregateType = aggregateType;
        outbox.aggregateId = aggregateId;
        outbox.eventType = eventType;
        outbox.payload = payload;
        outbox.idempotencyKey = idempotencyKey;
        outbox.status = OutboxStatus.PENDING;
        outbox.retryCount = 0;
        return outbox;
    }

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

    public void markPublished(LocalDateTime now) {
        this.status = OutboxStatus.PUBLISHED;
        this.publishedAt = now;
    }

    public void incrementRetry() {
        this.retryCount++;
    }

    public void markFailed() {
        this.status = OutboxStatus.FAILED;
    }
}
