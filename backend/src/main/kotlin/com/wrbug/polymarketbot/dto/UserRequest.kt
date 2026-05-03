package com.wrbug.polymarketbot.dto

data class UserCreateRequest(
    val username: String,
    val password: String
)

data class UserUpdatePasswordRequest(
    val userId: Long,
    val newPassword: String
)

data class UserUpdateOwnPasswordRequest(
    val newPassword: String
)

data class UserDeleteRequest(
    val userId: Long
)

