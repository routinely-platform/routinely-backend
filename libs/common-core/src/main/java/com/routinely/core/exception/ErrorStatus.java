package com.routinely.core.exception;

import lombok.Getter;

@Getter
public enum ErrorStatus {

    BAD_REQUEST(400),
    UNAUTHORIZED(401),
    FORBIDDEN(403),
    NOT_FOUND(404),
    CONFLICT(409),
    INTERNAL_SERVER_ERROR(500);

    private final int code;

    ErrorStatus(int code) {
        this.code = code;
    }
}
