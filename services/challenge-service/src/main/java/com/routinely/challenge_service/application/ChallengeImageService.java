package com.routinely.challenge_service.application;

import com.routinely.challenge_service.application.dto.ChallengeImageUploadCommand;
import com.routinely.challenge_service.domain.challenge.ChallengeImage;
import com.routinely.core.exception.BusinessException;
import com.routinely.core.exception.ErrorCode;
import com.routinely.storage.FileStorage;
import com.routinely.storage.FileUploadCommand;
import com.routinely.storage.StoredFile;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Set;

/**
 * 챌린지 대표 이미지 업로드/삭제.
 *
 * <p>저장은 공통 모듈 {@link FileStorage}(S3)에 위임하고, 결과 URL/ObjectKey 를 Challenge 에 반영한다.
 * 방장(leader)만 변경/삭제할 수 있으며, MIME/크기/시그니처 검증은 이 서비스 계층에서 수행한다.
 *
 * <p>이 클래스에는 트랜잭션이 없다. S3 전송은 트랜잭션 밖에서 하고, DB 반영만 {@link ChallengeImageWriter}가
 * 챌린지 행을 잠근 짧은 트랜잭션으로 한다 — 전송 동안 행 락을 잡으면 참여와 상태 전이가 함께 기다린다.
 * Writer가 반환되면 커밋된 것이므로 이전 오브젝트를 지우고, 예외면 롤백된 것이므로 새 오브젝트를 지운다.
 */
@Slf4j
@Service
public class ChallengeImageService {

    private static final String DIRECTORY = "challenge-images";
    private static final long MAX_FILE_SIZE_BYTES = 5L * 1024 * 1024; // 5MB
    private static final Set<String> ALLOWED_CONTENT_TYPES =
            Set.of("image/jpeg", "image/png", "image/webp");
    private static final byte[] PNG_SIGNATURE =
            {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};

    private final ChallengeImageWriter challengeImageWriter;
    private final FileStorage fileStorage;

    public ChallengeImageService(ChallengeImageWriter challengeImageWriter, FileStorage fileStorage) {
        this.challengeImageWriter = challengeImageWriter;
        this.fileStorage = fileStorage;
    }

    public void upload(ChallengeImageUploadCommand command) {
        challengeImageWriter.checkLeader(command.challengeId(), command.requesterUserId());
        validate(command);

        StoredFile stored = fileStorage.upload(new FileUploadCommand(
                DIRECTORY,
                command.originalFilename(),
                command.contentType(),
                command.size(),
                command.inputStream()));

        String previousObjectKey;
        try {
            previousObjectKey = challengeImageWriter.changeImage(
                    command.challengeId(), command.requesterUserId(), ChallengeImage.of(stored.url(), stored.key()));
        } catch (RuntimeException e) {
            deleteQuietly(stored.key());
            throw e;
        }
        deleteQuietly(previousObjectKey);
    }

    public void delete(Long challengeId, Long requesterUserId) {
        String objectKey = challengeImageWriter.removeImage(challengeId, requesterUserId);
        deleteQuietly(objectKey);
    }

    private void validate(ChallengeImageUploadCommand command) {
        if (command.size() <= 0) {
            throw new BusinessException(ErrorCode.EMPTY_FILE);
        }
        if (command.size() > MAX_FILE_SIZE_BYTES) {
            throw new BusinessException(ErrorCode.FILE_SIZE_EXCEEDED);
        }
        String contentType = command.contentType();
        if (contentType == null || !ALLOWED_CONTENT_TYPES.contains(contentType.toLowerCase(Locale.ROOT))) {
            throw new BusinessException(ErrorCode.UNSUPPORTED_IMAGE_TYPE);
        }
        if (!hasValidImageSignature(contentType, command.bytes())) {
            throw new BusinessException(ErrorCode.UNSUPPORTED_IMAGE_TYPE);
        }
    }

    private void deleteQuietly(String objectKey) {
        if (objectKey == null) {
            return;
        }
        try {
            fileStorage.delete(objectKey);
        } catch (RuntimeException e) {
            log.warn("챌린지 대표 이미지 오브젝트 삭제 실패 (objectKey={}). 고아 파일이 남을 수 있습니다.", objectKey, e);
        }
    }

    private boolean hasValidImageSignature(String contentType, byte[] bytes) {
        return switch (contentType.toLowerCase(Locale.ROOT)) {
            case "image/jpeg" -> isJpeg(bytes);
            case "image/png" -> isPng(bytes);
            case "image/webp" -> isWebp(bytes);
            default -> false;
        };
    }

    private boolean isJpeg(byte[] bytes) {
        return bytes.length >= 3
                && (bytes[0] & 0xFF) == 0xFF
                && (bytes[1] & 0xFF) == 0xD8
                && (bytes[2] & 0xFF) == 0xFF;
    }

    private boolean isPng(byte[] bytes) {
        if (bytes.length < PNG_SIGNATURE.length) {
            return false;
        }
        for (int i = 0; i < PNG_SIGNATURE.length; i++) {
            if (bytes[i] != PNG_SIGNATURE[i]) {
                return false;
            }
        }
        return true;
    }

    private boolean isWebp(byte[] bytes) {
        return bytes.length >= 12
                && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P';
    }
}
