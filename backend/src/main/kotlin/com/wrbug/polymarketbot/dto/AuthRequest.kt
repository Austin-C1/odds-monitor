package com.wrbug.polymarketbot.dto

data class LoginRequest(
    val username: String,
    val password: String
)

data class ResetPasswordRequest(
    val resetKey: String,
    val username: String,
    val newPassword: String
)

