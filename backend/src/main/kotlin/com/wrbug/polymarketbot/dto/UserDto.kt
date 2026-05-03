package com.wrbug.polymarketbot.dto

data class UserDto(
    val id: Long,
    val username: String,
    val isDefault: Boolean,
    val createdAt: Long,
    val updatedAt: Long
)

