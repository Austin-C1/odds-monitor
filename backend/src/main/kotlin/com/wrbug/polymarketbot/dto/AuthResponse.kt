package com.wrbug.polymarketbot.dto

data class LoginResponse(
    val token: String
)

data class CheckFirstUseResponse(
    val isFirstUse: Boolean
)

data class WebSocketTicketResponse(
    val ticket: String
)

