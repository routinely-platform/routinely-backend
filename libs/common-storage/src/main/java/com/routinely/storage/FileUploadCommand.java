package com.routinely.storage;

import java.io.InputStream;

/**
 * 파일 업로드 요청 정보.
 *
 * @param directory        저장 경로 prefix (예: {@code "routine-executions"})
 * @param originalFilename 원본 파일명 (확장자 추출용, null 허용)
 * @param contentType      MIME 타입 (예: {@code "image/jpeg"})
 * @param contentLength    바이트 크기 (S3 PutObject 에 필요)
 * @param inputStream      업로드할 바이트 스트림
 */
public record FileUploadCommand(
        String directory,
        String originalFilename,
        String contentType,
        long contentLength,
        InputStream inputStream
) {
    public FileUploadCommand {
        if (directory == null || directory.isBlank()) {
            throw new IllegalArgumentException("directory must not be blank");
        }
        if (inputStream == null) {
            throw new IllegalArgumentException("inputStream must not be null");
        }
        if (contentLength <= 0) {
            throw new IllegalArgumentException("contentLength must be positive");
        }
    }
}
