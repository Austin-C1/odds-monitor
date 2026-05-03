package com.wrbug.polymarketbot.dto

data class MarketBettingSearchRequest(
    val query: String,
    val limit: Int? = 5,
    val date: String? = null
)

data class MarketBettingDetailRequest(
    val query: String? = null,
    val slug: String? = null,
    val marketLimit: Int? = 30,
    val date: String? = null
)

data class MarketBettingSearchResponse(
    val query: String,
    val events: List<MarketBettingEventSummary>
)

data class MarketBettingEventSummary(
    val id: String,
    val slug: String,
    val title: String,
    val volume: String,
    val liquidity: String,
    val openInterest: String,
    val active: Boolean,
    val closed: Boolean,
    val marketsCount: Int,
    val url: String,
    val category: String? = null,
    val startDate: String? = null,
    val endDate: String? = null
)

data class MarketBettingEventDetail(
    val event: MarketBettingEventSummary,
    val markets: List<MarketBettingMarketDetail>
)

data class MarketBettingMarketDetail(
    val id: String,
    val conditionId: String,
    val slug: String,
    val question: String,
    val marketType: String,
    val line: String?,
    val groupItemTitle: String?,
    val volume: String,
    val liquidity: String,
    val outcomes: List<MarketBettingOutcomeDetail>
)

data class MarketBettingOutcomeDetail(
    val name: String,
    val tokenId: String,
    val odds: String,
    val tradedShares: String = "0",
    val tradedAmount: String = "0",
    val bidOrderAmount: String,
    val askOrderAmount: String,
    val topHolders: List<MarketBettingHolder>
)

data class MarketBettingHolder(
    val wallet: String,
    val name: String?,
    val shares: String,
    val profileUrl: String = if (wallet.isNotBlank()) "https://polymarket.com/profile/$wallet" else ""
)
