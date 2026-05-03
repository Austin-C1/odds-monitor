package com.wrbug.polymarketbot.dto

data class MarketDto(
    val id: String,
    val question: String,
    val slug: String,
    val category: String,
    val active: Boolean,
    val volume: String?,
    val liquidity: String?,
    val endDate: Long?,
    val createdAt: Long?,
    val updatedAt: Long?,
    val outcomes: List<OutcomeDto>?,
    val conditionId: String?,
    val description: String?,
    val image: String?,
    val icon: String?,
    val closed: Boolean?,
    val archived: Boolean?,
    val volumeNum: Double?,
    val liquidityNum: Double?,
    val bestBid: Double?,
    val bestAsk: Double?,
    val lastTradePrice: Double?,
    val events: List<MarketDto>? = null
)

data class OutcomeDto(
    val name: String,
    val price: String
)

data class EventListDto(
    val id: String,
    val title: String,
    val category: String,
    val active: Boolean,
    val markets: List<MarketDto>?,
    val createdAt: Long?
)

data class SeriesDto(
    val id: String,
    val title: String,
    val category: String,
    val events: List<EventListDto>?,
    val createdAt: Long?
)

data class CommentDto(
    val id: String,
    val market: String,
    val content: String,
    val parent: String?,
    val createdAt: Long,
    val user: String?
)
