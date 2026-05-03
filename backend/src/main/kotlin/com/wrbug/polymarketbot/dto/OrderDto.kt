package com.wrbug.polymarketbot.dto

data class OrderDto(
    val id: String,
    val market: String,
    val side: String,
    val price: String,
    val size: String,
    val filled: String,
    val status: String,
    val createdAt: Long
)

data class TradeDto(
    val id: String,
    val market: String,
    val side: String,
    val price: String,
    val size: String,
    val timestamp: Long,
    val user: String?
)

