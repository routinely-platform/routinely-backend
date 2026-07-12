package com.routinely.user_service.application.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.routinely.core.exception.BusinessException;
import com.routinely.core.exception.ErrorCode;
import com.routinely.storage.FileStorage;
import com.routinely.storage.FileUploadCommand;
import com.routinely.storage.StoredFile;
import com.routinely.user_service.application.user.dto.ProfileImageUploadCommand;
import com.routinely.user_service.domain.ProfileImage;
import com.routinely.user_service.domain.User;
import com.routinely.user_service.domain.UserRepository;
import com.routinely.user_service.domain.UserRole;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@DisplayName("ProfileImageService")
class ProfileImageServiceTest {

    private static final long USER_ID = 1L;

    private UserRepository userRepository;
    private FileStorage fileStorage;
    private ProfileImageService service;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        fileStorage = mock(FileStorage.class);
        service = new ProfileImageService(userRepository, fileStorage);
    }

    @Test
    @DisplayName("업로드_성공하면_저장소에업로드하고_User에URL과objectKey를반영한다")
    void upload_success() {
        User user = userWithoutImage();
        when(userRepository.findByIdAndIsActiveTrue(USER_ID)).thenReturn(Optional.of(user));
        StoredFile stored = new StoredFile(
                "profile-images/2026/07/new.jpg",
                "https://cdn.routinely.com/profile-images/2026/07/new.jpg");
        when(fileStorage.upload(any(FileUploadCommand.class))).thenReturn(stored);

        service.upload(command("image/jpeg", 1024));

        ArgumentCaptor<FileUploadCommand> captor = ArgumentCaptor.forClass(FileUploadCommand.class);
        verify(fileStorage).upload(captor.capture());
        assertThat(captor.getValue().directory()).isEqualTo("profile-images");
        assertThat(user.getProfileImageUrl()).isEqualTo(stored.url());
        assertThat(user.getProfileImageObjectKey()).isEqualTo(stored.key());
        verify(fileStorage, never()).delete(any());
    }

    @Test
    @DisplayName("업로드_기존이미지가있으면_새로업로드후_이전오브젝트삭제를예약한다")
    void upload_replacesAndDeletesPrevious() {
        User user = userWithImage("old-url", "profile-images/2026/06/old.jpg");
        when(userRepository.findByIdAndIsActiveTrue(USER_ID)).thenReturn(Optional.of(user));
        StoredFile stored = new StoredFile(
                "profile-images/2026/07/new.jpg",
                "https://cdn.routinely.com/profile-images/2026/07/new.jpg");
        when(fileStorage.upload(any(FileUploadCommand.class))).thenReturn(stored);

        service.upload(command("image/png", 2048));

        verify(fileStorage).delete("profile-images/2026/06/old.jpg");
        assertThat(user.getProfileImageObjectKey()).isEqualTo(stored.key());
    }

    @Test
    @DisplayName("업로드_트랜잭션중이면_이전오브젝트는_커밋후삭제한다")
    void upload_deletesPreviousAfterCommit() {
        User user = userWithImage("old-url", "profile-images/2026/06/old.jpg");
        when(userRepository.findByIdAndIsActiveTrue(USER_ID)).thenReturn(Optional.of(user));
        StoredFile stored = new StoredFile(
                "profile-images/2026/07/new.jpg",
                "https://cdn.routinely.com/profile-images/2026/07/new.jpg");
        when(fileStorage.upload(any(FileUploadCommand.class))).thenReturn(stored);

        TransactionSynchronizationManager.initSynchronization();
        try {
            service.upload(command("image/jpeg", 1024));

            verify(fileStorage, never()).delete(any());
            triggerCommit();

            verify(fileStorage).delete("profile-images/2026/06/old.jpg");
            verify(fileStorage, never()).delete("profile-images/2026/07/new.jpg");
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("업로드_트랜잭션롤백이면_새오브젝트를보상삭제한다")
    void upload_deletesNewObjectAfterRollback() {
        User user = userWithImage("old-url", "profile-images/2026/06/old.jpg");
        when(userRepository.findByIdAndIsActiveTrue(USER_ID)).thenReturn(Optional.of(user));
        StoredFile stored = new StoredFile(
                "profile-images/2026/07/new.jpg",
                "https://cdn.routinely.com/profile-images/2026/07/new.jpg");
        when(fileStorage.upload(any(FileUploadCommand.class))).thenReturn(stored);

        TransactionSynchronizationManager.initSynchronization();
        try {
            service.upload(command("image/jpeg", 1024));

            verify(fileStorage, never()).delete(any());
            triggerRollback();

            verify(fileStorage).delete("profile-images/2026/07/new.jpg");
            verify(fileStorage, never()).delete("profile-images/2026/06/old.jpg");
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("업로드_빈파일이면_EMPTY_FILE예외를던지고_저장소를호출하지않는다")
    void upload_emptyFile_throws() {
        ProfileImageUploadCommand command = command("image/jpeg", 0);
        assertThatThrownBy(() -> service.upload(command))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.EMPTY_FILE);
        verify(fileStorage, never()).upload(any());
    }

    @Test
    @DisplayName("업로드_허용되지않는MIME이면_UNSUPPORTED_IMAGE_TYPE예외를던진다")
    void upload_unsupportedType_throws() {
        ProfileImageUploadCommand command = command("application/pdf", 1024);
        assertThatThrownBy(() -> service.upload(command))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.UNSUPPORTED_IMAGE_TYPE);
        verify(fileStorage, never()).upload(any());
    }

    @Test
    @DisplayName("업로드_MIME은이미지지만_이미지시그니처가아니면_UNSUPPORTED_IMAGE_TYPE예외를던진다")
    void upload_invalidImageSignature_throws() {
        ProfileImageUploadCommand command = new ProfileImageUploadCommand(
                USER_ID,
                "photo.jpg",
                "image/jpeg",
                "not-an-image".getBytes());

        assertThatThrownBy(() -> service.upload(command))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.UNSUPPORTED_IMAGE_TYPE);
        verify(fileStorage, never()).upload(any());
    }

    @Test
    @DisplayName("업로드_최대크기를초과하면_FILE_SIZE_EXCEEDED예외를던진다")
    void upload_sizeExceeded_throws() {
        long tooBig = 5L * 1024 * 1024 + 1;
        ProfileImageUploadCommand command = command("image/jpeg", tooBig);
        assertThatThrownBy(() -> service.upload(command))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FILE_SIZE_EXCEEDED);
        verify(fileStorage, never()).upload(any());
    }

    @Test
    @DisplayName("업로드_사용자가없으면_USER_NOT_FOUND예외를던진다")
    void upload_userNotFound_throws() {
        when(userRepository.findByIdAndIsActiveTrue(USER_ID)).thenReturn(Optional.empty());

        ProfileImageUploadCommand command = command("image/jpeg", 1024);
        assertThatThrownBy(() -> service.upload(command))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.USER_NOT_FOUND);
        verify(fileStorage, never()).upload(any());
    }

    @Test
    @DisplayName("삭제_이미지가있으면_저장소에서삭제하고_User참조를제거한다")
    void delete_success() {
        User user = userWithImage("url", "profile-images/2026/06/old.jpg");
        when(userRepository.findByIdAndIsActiveTrue(USER_ID)).thenReturn(Optional.of(user));

        service.delete(USER_ID);

        verify(fileStorage).delete("profile-images/2026/06/old.jpg");
        assertThat(user.getProfileImageUrl()).isNull();
        assertThat(user.getProfileImageObjectKey()).isNull();
    }

    @Test
    @DisplayName("삭제_트랜잭션중이면_User참조를먼저제거하고_커밋후저장소에서삭제한다")
    void delete_deletesObjectAfterCommit() {
        User user = userWithImage("url", "profile-images/2026/06/old.jpg");
        when(userRepository.findByIdAndIsActiveTrue(USER_ID)).thenReturn(Optional.of(user));

        TransactionSynchronizationManager.initSynchronization();
        try {
            service.delete(USER_ID);

            assertThat(user.getProfileImageUrl()).isNull();
            assertThat(user.getProfileImageObjectKey()).isNull();
            verify(fileStorage, never()).delete(any());

            triggerCommit();

            verify(fileStorage).delete("profile-images/2026/06/old.jpg");
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("삭제_이미지가없으면_저장소를호출하지않는다")
    void delete_whenNoImage_noOp() {
        User user = userWithoutImage();
        when(userRepository.findByIdAndIsActiveTrue(USER_ID)).thenReturn(Optional.of(user));

        service.delete(USER_ID);

        verify(fileStorage, never()).delete(any());
    }

    private ProfileImageUploadCommand command(String contentType, long size) {
        byte[] bytes = imageBytes(contentType, Math.toIntExact(size));
        return new ProfileImageUploadCommand(USER_ID, "photo.jpg", contentType, bytes);
    }

    private byte[] imageBytes(String contentType, int size) {
        byte[] bytes = new byte[size];
        switch (contentType) {
            case "image/jpeg" -> {
                if (size >= 3) {
                    bytes[0] = (byte) 0xFF;
                    bytes[1] = (byte) 0xD8;
                    bytes[2] = (byte) 0xFF;
                }
            }
            case "image/png" -> {
                byte[] signature = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
                System.arraycopy(signature, 0, bytes, 0, Math.min(signature.length, size));
            }
            case "image/webp" -> {
                if (size >= 12) {
                    bytes[0] = 'R';
                    bytes[1] = 'I';
                    bytes[2] = 'F';
                    bytes[3] = 'F';
                    bytes[8] = 'W';
                    bytes[9] = 'E';
                    bytes[10] = 'B';
                    bytes[11] = 'P';
                }
            }
            default -> {
                if (size > 0) {
                    bytes[0] = 1;
                }
            }
        }
        return bytes;
    }

    private void triggerCommit() {
        for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
            synchronization.afterCommit();
        }
        for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
            synchronization.afterCompletion(TransactionSynchronization.STATUS_COMMITTED);
        }
    }

    private void triggerRollback() {
        for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
            synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
        }
    }

    private User userWithoutImage() {
        return User.builder()
                .id(USER_ID)
                .email("user@routinely.com")
                .passwordHash("hash")
                .nickname("루틴러")
                .role(UserRole.USER)
                .isActive(true)
                .build();
    }

    private User userWithImage(String url, String objectKey) {
        return User.builder()
                .id(USER_ID)
                .email("user@routinely.com")
                .passwordHash("hash")
                .nickname("루틴러")
                .role(UserRole.USER)
                .profileImage(ProfileImage.of(url, objectKey))
                .isActive(true)
                .build();
    }
}
