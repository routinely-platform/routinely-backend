package com.routinely.user_service.application.auth.dto;

public record SignUpCommand (
        String email,
        String nickname,
        String password
){}
