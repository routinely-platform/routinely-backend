package com.routinely.user_service.application.user;

import com.routinely.core.exception.BusinessException;
import com.routinely.core.exception.ErrorCode;
import com.routinely.storage.FileStorage;
import com.routinely.storage.FileUploadCommand;
import com.routinely.storage.StoredFile;
import com.routinely.user_service.application.user.dto.ProfileImageUploadCommand;
import com.routinely.user_service.domain.ProfileImage;
import com.routinely.user_service.domain.User;
import com.routinely.user_service.domain.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Locale;
import java.util.Set;

/**
 * 프로필 이미지 업로드/삭제.
 *
 * <p>저장은 공통 모듈 {@link FileStorage}(S3)에 위임하고, 결과 URL/ObjectKey 를 User 에 반영한다.
 * MIME/크기 등 정책 검증은 저장소가 아닌 이 서비스 계층에서 수행한다.
 */
@Slf4j
@Service
public class ProfileImageService {

    private static final String DIRECTORY = "profile-images";
    private static final long MAX_FILE_SIZE_BYTES = 5L * 1024 * 1024; // 5MB
    private static final Set<String> ALLOWED_CONTENT_TYPES =
            Set.of("image/jpeg", "image/png", "image/webp");
    private static final byte[] PNG_SIGNATURE =
            {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};

    private final UserRepository userRepository;
    private final FileStorage fileStorage;

    public ProfileImageService(UserRepository userRepository, FileStorage fileStorage) {
        this.userRepository = userRepository;
        this.fileStorage = fileStorage;
    }

    @Transactional
    public void upload(ProfileImageUploadCommand command) {
        validate(command);
        User user = getActiveUser(command.userId());

        String previousObjectKey = user.getProfileImageObjectKey();

        StoredFile stored = fileStorage.upload(new FileUploadCommand(
                DIRECTORY,
                command.originalFilename(),
                command.contentType(),
                command.size(),
                command.inputStream()));

        user.changeProfileImage(ProfileImage.of(stored.url(), stored.key()));

        deleteAfterCommit(previousObjectKey);
        deleteAfterRollback(stored.key());
    }

    @Transactional
    public void delete(Long userId) {
        User user = getActiveUser(userId);
        String objectKey = user.getProfileImageObjectKey();
        if (objectKey == null) {
            return;
        }
        user.removeProfileImage();
        deleteAfterCommit(objectKey);
    }

    private void validate(ProfileImageUploadCommand command) {
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

    private User getActiveUser(Long userId) {
        return userRepository.findByIdAndIsActiveTrue(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
    }

    private void deleteAfterCommit(String objectKey) {
        if (objectKey == null) {
            return;
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            deleteQuietly(objectKey);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                deleteQuietly(objectKey);
            }
        });
    }

    private void deleteAfterRollback(String objectKey) {
        if (objectKey == null || !TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) {
                    deleteQuietly(objectKey);
                }
            }
        });
    }

    private void deleteQuietly(String objectKey) {
        if (objectKey == null) {
            return;
        }
        try {
            fileStorage.delete(objectKey);
        } catch (RuntimeException e) {
            log.warn("프로필 이미지 오브젝트 삭제 실패 (objectKey={}). 고아 파일이 남을 수 있습니다.", objectKey, e);
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
