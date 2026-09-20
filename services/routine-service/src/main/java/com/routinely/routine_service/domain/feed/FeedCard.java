package com.routinely.routine_service.domain.feed;

import com.routinely.jpa.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDate;

@Entity
@Table(name = "feed_cards")
@Getter
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FeedCard extends BaseEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "routine_execution_id", nullable = false, unique = true)
    private Long routineExecutionId;
    @Column(name = "user_id", nullable = false)
    private Long userId;
    @Column(name = "challenge_id")
    private Long challengeId;
    @Column(name = "routine_title", nullable = false, length = 100)
    private String routineTitle;
    @Column(name = "scheduled_date", nullable = false)
    private LocalDate scheduledDate;
    @Column(name = "photo_url", length = 500)
    private String photoUrl;
    @Column(name = "photo_object_key", length = 500)
    private String photoObjectKey;
    @Column(name = "memo")
    private String memo;
}
