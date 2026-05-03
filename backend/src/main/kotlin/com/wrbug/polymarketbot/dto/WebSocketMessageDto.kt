package com.wrbug.polymarketbot.dto

enum class WebSocketMessageType(val value: Int) {
    SUB(1),
    UNSUB(2),
    DATA(3),
    SUB_ACK(4),
    PING(5),
    PONG(6);
    
    companion object {
        fun fromValue(value: Int): WebSocketMessageType? {
            return values().find { it.value == value }
        }
    }
}

data class WebSocketMessage(
    val type: Int,
    val channel: String? = null,
    val payload: Any? = null,
    val timestamp: Long? = null,
    val status: Int? = null,
    val message: String? = null
)

