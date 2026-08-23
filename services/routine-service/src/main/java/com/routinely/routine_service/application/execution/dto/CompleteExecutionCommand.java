package com.routinely.routine_service.application.execution.dto;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Objects;

/**
 * 루틴 실행 완료 요청. 완료는 새 COMPLETED 행 생성이므로 대상은 (routineId, scheduledDate)로 지정한다
 * (sparse 저장, ADR-0038). 인증 사진과 메모는 선택이다 — photoBytes가 null이면 사진 없이 완료 처리한다.
 *
 * @param routineId        완료 대상 루틴
 * @param userId           요청자 (소유권 검증용)
 * @param scheduledDate    완료 처리할 날짜(오늘만 허용)
 * @param originalFilename 원본 파일명 (확장자 추출용, 사진 없으면 null)
 * @param contentType      MIME 타입 (사진 없으면 null)
 * @param photoBytes       인증 사진 바이트 (선택)
 * @param memo             완료 메모 (선택)
 */
public record CompleteExecutionCommand(
        Long routineId,
        Long userId,
        LocalDate scheduledDate,
        String originalFilename,
        String contentType,
        byte[] photoBytes,
        String memo
) {
    public CompleteExecutionCommand {
        photoBytes = photoBytes == null ? null : Arrays.copyOf(photoBytes, photoBytes.length);
    }

    public boolean hasPhoto() {
        return photoBytes != null && photoBytes.length > 0;
    }

    public long size() {
        return photoBytes == null ? 0 : photoBytes.length;
    }

    @Override
    public byte[] photoBytes() {
        return photoBytes == null ? null : Arrays.copyOf(photoBytes, photoBytes.length);
    }

    public InputStream inputStream() {
        return new ByteArrayInputStream(photoBytes);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        CompleteExecutionCommand that = (CompleteExecutionCommand) o;
        return Objects.equals(routineId, that.routineId)
                && Objects.equals(userId, that.userId)
                && Objects.equals(scheduledDate, that.scheduledDate)
                && Objects.equals(originalFilename, that.originalFilename)
                && Objects.equals(contentType, that.contentType)
                && Arrays.equals(photoBytes, that.photoBytes)
                && Objects.equals(memo, that.memo);
    }

    @Override
    public int hashCode() {
        return 31 * Objects.hash(routineId, userId, scheduledDate, originalFilename, contentType, memo)
                + Arrays.hashCode(photoBytes);
    }

    @Override
    public String toString() {
        return "CompleteExecutionCommand{routineId=" + routineId
                + ", userId=" + userId
                + ", scheduledDate=" + scheduledDate
                + ", originalFilename=" + originalFilename
                + ", contentType=" + contentType
                + ", photoBytes=" + (photoBytes == null ? "null" : photoBytes.length + " bytes")
                + ", memo=" + memo
                + "}";
    }
}
