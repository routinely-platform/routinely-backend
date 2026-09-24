package com.routinely.routine_service.domain.outbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

@Entity
@Table(name = "routine_outbox")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RoutineOutbox {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "aggregate_type", nullable = false, length = 50)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false)
    private Long aggregateId;

    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;

    @Column(name = "partition_key", length = 100)
    private String partitionKey;

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

    public static RoutineOutbox create(String aggregateType, Long aggregateId,
                                         String eventType, String payload,
                                         String idempotencyKey, String partitionKey,
                                         LocalDateTime createdAt) {
        RoutineOutbox outbox = new RoutineOutbox();
        outbox.aggregateType = aggregateType;
        outbox.aggregateId = aggregateId;
        outbox.eventType = eventType;
        outbox.payload = payload;
        outbox.idempotencyKey = idempotencyKey;
        outbox.partitionKey = partitionKey;
        outbox.status = OutboxStatus.PENDING;
        outbox.retryCount = 0;
        // 폴링 정렬 키라 호출부의 Clock으로 정한다 — 테스트에서 시각을 고정할 수 있다.
        outbox.createdAt = createdAt;
        return outbox;
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
