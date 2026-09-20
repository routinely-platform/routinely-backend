package com.routinely.routine_service.presentation.rest.execution;
import com.routinely.core.exception.BusinessException;
import com.routinely.core.exception.ErrorCode;
import com.routinely.routine_service.application.execution.dto.CompleteExecutionCommand;
import java.util.Locale;
import java.util.Set;
final class ExecutionInputValidator {
    private static final long MAX_FILE_SIZE_BYTES = 5L * 1024 * 1024; // 5MB
    private static final int MAX_MEMO_LENGTH = 500;
    private static final Set<String> ALLOWED_CONTENT_TYPES =
            Set.of("image/jpeg", "image/png", "image/webp");
    private static final byte[] PNG_SIGNATURE =
            {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};

    void validate(CompleteExecutionCommand command) {
        validateMemo(command.memo());
        if (command.hasPhoto()) validatePhoto(command);
    }
    private void validateMemo(String memo) {
        if (memo != null && memo.length() > MAX_MEMO_LENGTH) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "메모는 %d자 이하여야 합니다.".formatted(MAX_MEMO_LENGTH));
        }
    }

    private void validatePhoto(CompleteExecutionCommand command) {
        if (command.size() > MAX_FILE_SIZE_BYTES) {
            throw new BusinessException(ErrorCode.FILE_SIZE_EXCEEDED);
        }
        String contentType = command.contentType();
        if (contentType == null || !ALLOWED_CONTENT_TYPES.contains(contentType.toLowerCase(Locale.ROOT))) {
            throw new BusinessException(ErrorCode.UNSUPPORTED_IMAGE_TYPE);
        }
        if (!hasValidImageSignature(contentType, command.photoBytes())) {
            throw new BusinessException(ErrorCode.UNSUPPORTED_IMAGE_TYPE);
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
