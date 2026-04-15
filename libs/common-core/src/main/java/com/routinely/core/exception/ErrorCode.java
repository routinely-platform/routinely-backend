package com.routinely.core.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public enum ErrorCode {

    // Common
    VALIDATION_FAILED("VALIDATION_FAILED", "유효성 검사에 실패했습니다.", HttpStatus.BAD_REQUEST),
    UNAUTHORIZED("UNAUTHORIZED", "인증이 필요합니다.", HttpStatus.UNAUTHORIZED),
    FORBIDDEN("FORBIDDEN", "접근 권한이 없습니다.", HttpStatus.FORBIDDEN),
    INTERNAL_SERVER_ERROR("INTERNAL_SERVER_ERROR", "서버 오류가 발생했습니다.", HttpStatus.INTERNAL_SERVER_ERROR),

    // User
    USER_NOT_FOUND("USER_NOT_FOUND", "사용자를 찾을 수 없습니다.", HttpStatus.NOT_FOUND),
    EMAIL_ALREADY_EXISTS("EMAIL_ALREADY_EXISTS", "이미 사용 중인 이메일입니다.", HttpStatus.CONFLICT),
    NICKNAME_ALREADY_EXISTS("NICKNAME_ALREADY_EXISTS", "이미 사용 중인 닉네임입니다.", HttpStatus.CONFLICT),
    INVALID_CREDENTIALS("INVALID_CREDENTIALS", "이메일 또는 비밀번호가 올바르지 않습니다.", HttpStatus.UNAUTHORIZED),

    // Challenge
    CHALLENGE_NOT_FOUND("CHALLENGE_NOT_FOUND", "챌린지를 찾을 수 없습니다.", HttpStatus.NOT_FOUND),
    CHALLENGE_ALREADY_JOINED("CHALLENGE_ALREADY_JOINED", "이미 참여한 챌린지입니다.", HttpStatus.CONFLICT),
    CHALLENGE_FULL("CHALLENGE_FULL", "챌린지 인원이 가득 찼습니다.", HttpStatus.CONFLICT),
    NOT_CHALLENGE_MEMBER("NOT_CHALLENGE_MEMBER", "챌린지 멤버가 아닙니다.", HttpStatus.FORBIDDEN),
    CHALLENGE_ALREADY_ENDED("CHALLENGE_ALREADY_ENDED", "이미 종료된 챌린지입니다.", HttpStatus.CONFLICT),
    CHALLENGE_NOT_STARTED("CHALLENGE_NOT_STARTED", "아직 시작되지 않은 챌린지입니다.", HttpStatus.CONFLICT),

    // Routine
    ROUTINE_TEMPLATE_NOT_FOUND("ROUTINE_TEMPLATE_NOT_FOUND", "루틴 템플릿을 찾을 수 없습니다.", HttpStatus.NOT_FOUND),
    ROUTINE_NOT_FOUND("ROUTINE_NOT_FOUND", "루틴을 찾을 수 없습니다.", HttpStatus.NOT_FOUND),
    EXECUTION_NOT_FOUND("EXECUTION_NOT_FOUND", "루틴 수행 기록을 찾을 수 없습니다.", HttpStatus.NOT_FOUND),
    EXECUTION_ALREADY_COMPLETED("EXECUTION_ALREADY_COMPLETED", "이미 완료된 수행 기록입니다.", HttpStatus.CONFLICT),

    // Chat
    CHAT_ROOM_NOT_FOUND("CHAT_ROOM_NOT_FOUND", "채팅방을 찾을 수 없습니다.", HttpStatus.NOT_FOUND),
    CHAT_NOT_MEMBER("CHAT_NOT_MEMBER", "채팅방 멤버가 아닙니다.", HttpStatus.FORBIDDEN),

    // Notification
    NOTIFICATION_NOT_FOUND("NOTIFICATION_NOT_FOUND", "알림을 찾을 수 없습니다.", HttpStatus.NOT_FOUND);

    private final String code;
    private final String message;
    private final HttpStatus httpStatus;

    ErrorCode(String code, String message, HttpStatus httpStatus) {
        this.code = code;
        this.message = message;
        this.httpStatus = httpStatus;
    }
}
