package com.routinely.challenge_service.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.routinely.challenge_service.application.dto.ChallengeImageUploadCommand;
import com.routinely.challenge_service.domain.challenge.Challenge;
import com.routinely.challenge_service.domain.challenge.ChallengeImage;
import com.routinely.challenge_service.domain.challenge.ChallengeRepository;
import com.routinely.challenge_service.domain.member.ChallengeMember;
import com.routinely.challenge_service.domain.member.ChallengeMemberRepository;
import com.routinely.challenge_service.domain.member.ChallengeMemberRole;
import com.routinely.challenge_service.domain.member.MembershipStatus;
import com.routinely.core.exception.BusinessException;
import com.routinely.core.exception.ErrorCode;
import com.routinely.storage.FileStorage;
import com.routinely.storage.FileUploadCommand;
import com.routinely.storage.StoredFile;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@DisplayName("ChallengeImageService")
class ChallengeImageServiceTest {

    private static final long CHALLENGE_ID = 1L;
    private static final long LEADER_ID = 10L;
    private static final String OLD_KEY = "challenge-images/2026/06/old.jpg";

    private ChallengeRepository challengeRepository;
    private ChallengeMemberRepository challengeMemberRepository;
    private FileStorage fileStorage;
    private ChallengeImageService service;

    @BeforeEach
    void setUp() {
        challengeRepository = mock(ChallengeRepository.class);
        challengeMemberRepository = mock(ChallengeMemberRepository.class);
        fileStorage = mock(FileStorage.class);
        service = new ChallengeImageService(challengeRepository, challengeMemberRepository, fileStorage);
    }

    @Test
    @DisplayName("업로드_방장이면_저장소에업로드하고_Challenge에URL과objectKey를반영한다")
    void upload_success() {
        Challenge challenge = givenChallengeWithLeader(null);
        when(fileStorage.upload(any(FileUploadCommand.class))).thenReturn(newStored());

        service.upload(command("image/jpeg", 1024));

        ArgumentCaptor<FileUploadCommand> captor = ArgumentCaptor.forClass(FileUploadCommand.class);
        verify(fileStorage).upload(captor.capture());
        assertThat(captor.getValue().directory()).isEqualTo("challenge-images");
        assertThat(challenge.getImageUrl()).isEqualTo(newStored().url());
        assertThat(challenge.getImageObjectKey()).isEqualTo(newStored().key());
        verify(fileStorage, never()).delete(any());
    }

    @Test
    @DisplayName("업로드_기존이미지가있으면_새로업로드후_이전오브젝트를삭제한다")
    void upload_replacesAndDeletesPrevious() {
        Challenge challenge = givenChallengeWithLeader(ChallengeImage.of("old-url", OLD_KEY));
        when(fileStorage.upload(any(FileUploadCommand.class))).thenReturn(newStored());

        service.upload(command("image/png", 2048));

        verify(fileStorage).delete(OLD_KEY);
        assertThat(challenge.getImageObjectKey()).isEqualTo(newStored().key());
    }

    @Test
    @DisplayName("업로드_트랜잭션중이면_이전오브젝트는_커밋후삭제한다")
    void upload_deletesPreviousAfterCommit() {
        givenChallengeWithLeader(ChallengeImage.of("old-url", OLD_KEY));
        when(fileStorage.upload(any(FileUploadCommand.class))).thenReturn(newStored());

        TransactionSynchronizationManager.initSynchronization();
        try {
            service.upload(command("image/jpeg", 1024));

            verify(fileStorage, never()).delete(any());
            triggerCommit();

            verify(fileStorage).delete(OLD_KEY);
            verify(fileStorage, never()).delete(newStored().key());
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("업로드_트랜잭션롤백이면_새오브젝트를보상삭제한다")
    void upload_deletesNewObjectAfterRollback() {
        givenChallengeWithLeader(ChallengeImage.of("old-url", OLD_KEY));
        when(fileStorage.upload(any(FileUploadCommand.class))).thenReturn(newStored());

        TransactionSynchronizationManager.initSynchronization();
        try {
            service.upload(command("image/jpeg", 1024));

            verify(fileStorage, never()).delete(any());
            triggerRollback();

            verify(fileStorage).delete(newStored().key());
            verify(fileStorage, never()).delete(OLD_KEY);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("업로드_챌린지가없으면_CHALLENGE_NOT_FOUND예외를던지고_저장소를호출하지않는다")
    void upload_challengeNotFound_throws() {
        when(challengeRepository.findById(CHALLENGE_ID)).thenReturn(Optional.empty());

        ChallengeImageUploadCommand command = command("image/jpeg", 1024);
        assertThatThrownBy(() -> service.upload(command))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.CHALLENGE_NOT_FOUND);
        verify(fileStorage, never()).upload(any());
    }

    @Test
    @DisplayName("업로드_멤버가아니면_NOT_CHALLENGE_MEMBER예외를던진다")
    void upload_notMember_throws() {
        when(challengeRepository.findById(CHALLENGE_ID)).thenReturn(Optional.of(challenge(null)));
        when(challengeMemberRepository.findByChallengeIdAndUserIdAndStatus(CHALLENGE_ID, LEADER_ID, MembershipStatus.ACTIVE))
                .thenReturn(Optional.empty());

        ChallengeImageUploadCommand command = command("image/jpeg", 1024);
        assertThatThrownBy(() -> service.upload(command))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.NOT_CHALLENGE_MEMBER);
        verify(fileStorage, never()).upload(any());
    }

    @Test
    @DisplayName("업로드_방장이아니면_FORBIDDEN예외를던진다")
    void upload_notLeader_throws() {
        when(challengeRepository.findById(CHALLENGE_ID)).thenReturn(Optional.of(challenge(null)));
        when(challengeMemberRepository.findByChallengeIdAndUserIdAndStatus(CHALLENGE_ID, LEADER_ID, MembershipStatus.ACTIVE))
                .thenReturn(Optional.of(member(ChallengeMemberRole.MEMBER)));

        ChallengeImageUploadCommand command = command("image/jpeg", 1024);
        assertThatThrownBy(() -> service.upload(command))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FORBIDDEN);
        verify(fileStorage, never()).upload(any());
    }

    @Test
    @DisplayName("업로드_빈파일이면_EMPTY_FILE예외를던진다")
    void upload_emptyFile_throws() {
        givenChallengeWithLeader(null);

        ChallengeImageUploadCommand command = command("image/jpeg", 0);
        assertThatThrownBy(() -> service.upload(command))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.EMPTY_FILE);
        verify(fileStorage, never()).upload(any());
    }

    @Test
    @DisplayName("업로드_허용되지않는MIME이면_UNSUPPORTED_IMAGE_TYPE예외를던진다")
    void upload_unsupportedType_throws() {
        givenChallengeWithLeader(null);

        ChallengeImageUploadCommand command = command("application/pdf", 1024);
        assertThatThrownBy(() -> service.upload(command))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.UNSUPPORTED_IMAGE_TYPE);
        verify(fileStorage, never()).upload(any());
    }

    @Test
    @DisplayName("업로드_MIME은이미지지만_시그니처가아니면_UNSUPPORTED_IMAGE_TYPE예외를던진다")
    void upload_invalidImageSignature_throws() {
        givenChallengeWithLeader(null);

        ChallengeImageUploadCommand command = new ChallengeImageUploadCommand(
                CHALLENGE_ID, LEADER_ID, "cover.jpg", "image/jpeg", "not-an-image".getBytes());
        assertThatThrownBy(() -> service.upload(command))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.UNSUPPORTED_IMAGE_TYPE);
        verify(fileStorage, never()).upload(any());
    }

    @Test
    @DisplayName("업로드_최대크기를초과하면_FILE_SIZE_EXCEEDED예외를던진다")
    void upload_sizeExceeded_throws() {
        givenChallengeWithLeader(null);

        long tooBig = 5L * 1024 * 1024 + 1;
        ChallengeImageUploadCommand command = command("image/jpeg", tooBig);
        assertThatThrownBy(() -> service.upload(command))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FILE_SIZE_EXCEEDED);
        verify(fileStorage, never()).upload(any());
    }

    @Test
    @DisplayName("삭제_방장이고_이미지가있으면_저장소에서삭제하고_Challenge참조를제거한다")
    void delete_success() {
        Challenge challenge = givenChallengeWithLeader(ChallengeImage.of("url", OLD_KEY));

        service.delete(CHALLENGE_ID, LEADER_ID);

        verify(fileStorage).delete(OLD_KEY);
        assertThat(challenge.getImageUrl()).isNull();
        assertThat(challenge.getImageObjectKey()).isNull();
    }

    @Test
    @DisplayName("삭제_트랜잭션중이면_참조를먼저제거하고_커밋후저장소에서삭제한다")
    void delete_deletesObjectAfterCommit() {
        Challenge challenge = givenChallengeWithLeader(ChallengeImage.of("url", OLD_KEY));

        TransactionSynchronizationManager.initSynchronization();
        try {
            service.delete(CHALLENGE_ID, LEADER_ID);

            assertThat(challenge.getImageObjectKey()).isNull();
            verify(fileStorage, never()).delete(any());

            triggerCommit();

            verify(fileStorage).delete(OLD_KEY);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("삭제_이미지가없으면_저장소를호출하지않는다")
    void delete_whenNoImage_noOp() {
        givenChallengeWithLeader(null);

        service.delete(CHALLENGE_ID, LEADER_ID);

        verify(fileStorage, never()).delete(any());
    }

    @Test
    @DisplayName("삭제_방장이아니면_FORBIDDEN예외를던진다")
    void delete_notLeader_throws() {
        when(challengeRepository.findById(CHALLENGE_ID)).thenReturn(Optional.of(challenge(ChallengeImage.of("url", OLD_KEY))));
        when(challengeMemberRepository.findByChallengeIdAndUserIdAndStatus(CHALLENGE_ID, LEADER_ID, MembershipStatus.ACTIVE))
                .thenReturn(Optional.of(member(ChallengeMemberRole.MEMBER)));

        assertThatThrownBy(() -> service.delete(CHALLENGE_ID, LEADER_ID))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FORBIDDEN);
        verify(fileStorage, never()).delete(any());
    }

    private Challenge givenChallengeWithLeader(ChallengeImage image) {
        Challenge challenge = challenge(image);
        when(challengeRepository.findById(CHALLENGE_ID)).thenReturn(Optional.of(challenge));
        when(challengeMemberRepository.findByChallengeIdAndUserIdAndStatus(CHALLENGE_ID, LEADER_ID, MembershipStatus.ACTIVE))
                .thenReturn(Optional.of(member(ChallengeMemberRole.LEADER)));
        return challenge;
    }

    private Challenge challenge(ChallengeImage image) {
        return Challenge.builder()
                .id(CHALLENGE_ID)
                .creatorUserId(LEADER_ID)
                .title("매일 30분 독서")
                .maxMembers(10)
                .categoryCode("READING")
                .image(image)
                .build();
    }

    private ChallengeMember member(ChallengeMemberRole role) {
        return ChallengeMember.builder()
                .role(role)
                .status(MembershipStatus.ACTIVE)
                .build();
    }

    private StoredFile newStored() {
        return new StoredFile(
                "challenge-images/2026/07/new.jpg",
                "https://cdn.routinely.com/challenge-images/2026/07/new.jpg");
    }

    private ChallengeImageUploadCommand command(String contentType, long size) {
        byte[] bytes = imageBytes(contentType, Math.toIntExact(size));
        return new ChallengeImageUploadCommand(CHALLENGE_ID, LEADER_ID, "cover.jpg", contentType, bytes);
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
}
