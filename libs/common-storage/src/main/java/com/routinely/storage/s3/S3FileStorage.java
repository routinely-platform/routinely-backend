package com.routinely.storage.s3;

import com.routinely.core.exception.BusinessException;
import com.routinely.core.exception.ErrorCode;
import com.routinely.storage.FileStorage;
import com.routinely.storage.FileUploadCommand;
import com.routinely.storage.StoredFile;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * AWS SDK v2 기반 {@link FileStorage} 구현체.
 *
 * <p>오브젝트 키는 {@code {directory}/{yyyy}/{MM}/{uuid}.{ext}} 규칙으로 생성한다.
 */
public class S3FileStorage implements FileStorage {

    private static final DateTimeFormatter DATE_PATH = DateTimeFormatter.ofPattern("yyyy/MM");
    private static final Pattern SAFE_EXTENSION = Pattern.compile("[a-z0-9]{1,10}");

    private final S3Client s3Client;
    private final S3Properties properties;

    public S3FileStorage(S3Client s3Client, S3Properties properties) {
        this.s3Client = s3Client;
        this.properties = properties;
    }

    @Override
    public StoredFile upload(FileUploadCommand command) {
        String key = buildKey(command);
        try {
            PutObjectRequest request = PutObjectRequest.builder()
                    .bucket(properties.getBucket())
                    .key(key)
                    .contentType(command.contentType())
                    .contentLength(command.contentLength())
                    .build();
            s3Client.putObject(request,
                    RequestBody.fromInputStream(command.inputStream(), command.contentLength()));
            return new StoredFile(key, resolveUrl(key));
        } catch (SdkException e) {
            throw new BusinessException(ErrorCode.FILE_UPLOAD_FAILED, "파일 업로드에 실패했습니다: " + key, e);
        }
    }

    @Override
    public void delete(String key) {
        if (!hasText(key)) {
            throw new IllegalArgumentException("key must not be blank");
        }
        try {
            s3Client.deleteObject(DeleteObjectRequest.builder()
                    .bucket(properties.getBucket())
                    .key(key)
                    .build());
        } catch (SdkException e) {
            throw new BusinessException(ErrorCode.FILE_DELETE_FAILED, "파일 삭제에 실패했습니다: " + key, e);
        }
    }

    /** {@code {directory}/{yyyy}/{MM}/{uuid}.{ext}} 형태의 오브젝트 키를 만든다. */
    String buildKey(FileUploadCommand command) {
        String datePath = LocalDate.now().format(DATE_PATH);
        String filename = UUID.randomUUID() + resolveExtension(command);
        return "%s/%s/%s".formatted(normalizeDirectory(command.directory()), datePath, filename);
    }

    /** 저장된 오브젝트에 접근 가능한 URL 을 만든다. 공개 base URL 이 있으면 우선 사용한다. */
    String resolveUrl(String key) {
        if (hasText(properties.getPublicBaseUrl())) {
            return "%s/%s".formatted(trimTrailingSlash(properties.getPublicBaseUrl()), key);
        }
        if (hasText(properties.getEndpoint())) {
            // LocalStack 등 endpoint override + path-style
            return "%s/%s/%s".formatted(trimTrailingSlash(properties.getEndpoint()), properties.getBucket(), key);
        }
        if (properties.isPathStyleAccess()) {
            return "https://s3.%s.amazonaws.com/%s/%s".formatted(properties.getRegion(), properties.getBucket(), key);
        }
        // 실 AWS virtual-hosted-style
        return "https://%s.s3.%s.amazonaws.com/%s".formatted(properties.getBucket(), properties.getRegion(), key);
    }

    private static String resolveExtension(FileUploadCommand command) {
        String extension = extensionFromContentType(command.contentType());
        if (extension != null) {
            return extension;
        }
        return extractSafeExtension(command.originalFilename());
    }

    private static String extensionFromContentType(String contentType) {
        if (!hasText(contentType)) {
            return null;
        }
        return switch (contentType.toLowerCase(Locale.ROOT)) {
            case "image/jpeg", "image/jpg" -> ".jpg";
            case "image/png" -> ".png";
            case "image/webp" -> ".webp";
            case "image/gif" -> ".gif";
            default -> null;
        };
    }

    private static String extractSafeExtension(String filename) {
        if (filename == null) {
            return "";
        }
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) {
            return "";
        }
        String extension = filename.substring(dot + 1).toLowerCase(Locale.ROOT);
        return SAFE_EXTENSION.matcher(extension).matches() ? "." + extension : "";
    }

    private static String normalizeDirectory(String directory) {
        String normalized = directory.strip().replace('\\', '/');
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("directory must be a safe relative path");
        }
        for (String segment : normalized.split("/")) {
            if (segment.isBlank() || segment.equals(".") || segment.equals("..")) {
                throw new IllegalArgumentException("directory must be a safe relative path");
            }
        }
        return normalized;
    }

    private static String trimTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
