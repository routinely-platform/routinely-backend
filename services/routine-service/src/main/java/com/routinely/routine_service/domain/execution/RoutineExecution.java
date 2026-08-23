package com.routinely.routine_service.domain.execution;

import com.routinely.jpa.entity.BaseEntity;
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

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 루틴 일별 실행 기록 — 완료한 날짜의 인증 정보(사진/메모)를 담는다.
 *
 * <p><b>sparse 저장</b>(ADR-0038): 루틴 시작 시 전 기간을 사전 생성하지 않고, <b>사용자가 명시적으로 남긴
 * 행동</b>만 행으로 만든다. 현재 그 행동은 완료뿐이라 저장되는 행도 {@code COMPLETED}뿐이며, 시스템이
 * 판단하는 PENDING/MISSED는 조회 시 스케줄 due 판정(ADR-0039)으로 파생한다.
 * {@code uq_re_routine_date} UNIQUE(routine_id, scheduled_date)로 하루 1건이 보장되며(ADR-0013 멱등성),
 * 완료 취소는 상태 전이가 아니라 행 삭제다.
 *
 * <p>{@code created_at} / {@code updated_at} 은 {@link BaseEntity}가 JPA Auditing으로 관리한다.
 */
@Entity
@Table(name = "routine_executions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RoutineExecution extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "routine_id", nullable = false)
    private Long routineId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "scheduled_date", nullable = false)
    private LocalDate scheduledDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ExecutionStatus status;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "photo_url", length = 500)
    private String photoUrl;

    @Column(name = "photo_object_key", length = 500)
    private String photoObjectKey;

    @Column(name = "memo")
    private String memo;

    /**
     * 완료 실행 기록을 생성한다(sparse). 완료 시각과 인증 정보(사진 URL/오브젝트 키, 메모)를 함께 기록한다.
     * completed_at은 {@code ck_re_completed_at} CHECK상 COMPLETED에서 필수다.
     */
    public static RoutineExecution completed(Long routineId, Long userId, LocalDate scheduledDate,
                                             LocalDateTime completedAt, String photoUrl,
                                             String photoObjectKey, String memo) {
        RoutineExecution execution = new RoutineExecution();
        execution.routineId = routineId;
        execution.userId = userId;
        execution.scheduledDate = scheduledDate;
        execution.status = ExecutionStatus.COMPLETED;
        execution.completedAt = completedAt;
        execution.photoUrl = photoUrl;
        execution.photoObjectKey = photoObjectKey;
        execution.memo = memo;
        return execution;
    }
}
