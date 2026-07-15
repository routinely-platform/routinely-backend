package com.routinely.challenge_service.application.dto;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Objects;

/**
 * 챌린지 대표 이미지 업로드 요청.
 *
 * @param challengeId      대상 챌린지
 * @param requesterUserId  요청자 (방장 검증용)
 * @param originalFilename 원본 파일명 (확장자 추출용)
 * @param contentType      MIME 타입
 * @param bytes            업로드할 이미지 바이트
 */
public record ChallengeImageUploadCommand(
        Long challengeId,
        Long requesterUserId,
        String originalFilename,
        String contentType,
        byte[] bytes
) {
    public ChallengeImageUploadCommand {
        bytes = bytes == null ? null : Arrays.copyOf(bytes, bytes.length);
    }

    public long size() {
        return bytes == null ? 0 : bytes.length;
    }

    @Override
    public byte[] bytes() {
        return bytes == null ? null : Arrays.copyOf(bytes, bytes.length);
    }

    public InputStream inputStream() {
        return new ByteArrayInputStream(bytes);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        ChallengeImageUploadCommand that = (ChallengeImageUploadCommand) o;
        return Objects.equals(challengeId, that.challengeId)
                && Objects.equals(requesterUserId, that.requesterUserId)
                && Objects.equals(originalFilename, that.originalFilename)
                && Objects.equals(contentType, that.contentType)
                && Arrays.equals(bytes, that.bytes);
    }

    @Override
    public int hashCode() {
        return 31 * Objects.hash(challengeId, requesterUserId, originalFilename, contentType)
                + Arrays.hashCode(bytes);
    }

    @Override
    public String toString() {
        return "ChallengeImageUploadCommand{challengeId=" + challengeId
                + ", requesterUserId=" + requesterUserId
                + ", originalFilename=" + originalFilename
                + ", contentType=" + contentType
                + ", bytes=" + (bytes == null ? "null" : bytes.length + " bytes")
                + "}";
    }
}
