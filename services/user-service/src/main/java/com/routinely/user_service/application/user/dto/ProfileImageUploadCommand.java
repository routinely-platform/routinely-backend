package com.routinely.user_service.application.user.dto;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Objects;

/**
 * 프로필 이미지 업로드 요청.
 *
 * @param userId           업로드 대상 사용자
 * @param originalFilename 원본 파일명 (확장자 추출용)
 * @param contentType      MIME 타입
 * @param bytes            업로드할 이미지 바이트
 */
public record ProfileImageUploadCommand(
        Long userId,
        String originalFilename,
        String contentType,
        byte[] bytes
) {
    public ProfileImageUploadCommand {
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
        ProfileImageUploadCommand that = (ProfileImageUploadCommand) o;
        return Objects.equals(userId, that.userId)
                && Objects.equals(originalFilename, that.originalFilename)
                && Objects.equals(contentType, that.contentType)
                && Arrays.equals(bytes, that.bytes);
    }

    @Override
    public int hashCode() {
        return 31 * Objects.hash(userId, originalFilename, contentType) + Arrays.hashCode(bytes);
    }

    @Override
    public String toString() {
        return "ProfileImageUploadCommand{userId=" + userId
                + ", originalFilename=" + originalFilename
                + ", contentType=" + contentType
                + ", bytes=" + (bytes == null ? "null" : bytes.length + " bytes")
                + "}";
    }
}
