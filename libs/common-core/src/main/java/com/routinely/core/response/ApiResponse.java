package com.routinely.core.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@JsonInclude(JsonInclude.Include.NON_NULL)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ApiResponse<T> {

    private boolean success;
    private String message;
    private String errorCode;
    private T data;

    public static <T> ApiResponse<T> ok(String message, T data) {
        ApiResponse<T> response = new ApiResponse<>();
        response.success = true;
        response.message = message;
        response.data = data;
        return response;
    }

    public static <T> ApiResponse<T> ok(String message) {
        ApiResponse<T> response = new ApiResponse<>();
        response.success = true;
        response.message = message;
        return response;
    }

    public static <T> ApiResponse<T> fail(String errorCode, String message) {
        ApiResponse<T> response = new ApiResponse<>();
        response.success = false;
        response.errorCode = errorCode;
        response.message = message;
        return response;
    }

    public static <T> ApiResponse<T> fail(String errorCode, String message, T data) {
        ApiResponse<T> response = new ApiResponse<>();
        response.success = false;
        response.errorCode = errorCode;
        response.message = message;
        response.data = data;
        return response;
    }
}
