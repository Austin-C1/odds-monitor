package com.wrbug.polymarketbot.dto

enum class PositionPushMessageType {
    FULL,
    INCREMENTAL
}

data class PositionPushMessage(
    val type: PositionPushMessageType,
    val timestamp: Long,
    val currentPositions: List<AccountPositionDto> = emptyList(),
    val historyPositions: List<AccountPositionDto> = emptyList(),
    val removedPositionKeys: List<String> = emptyList()
)

fun AccountPositionDto.getPositionKey(): String {
    return "${accountId}-${marketId}-${side}"
}

