package com.wrbug.polymarketbot.api

import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Base URL: https://gamma-api.polymarket.com
 */
interface PolymarketGammaApi {

    @GET("/markets")
    suspend fun listMarkets(
        @Query("condition_ids") conditionIds: List<String>? = null,
        @Query("clob_token_ids") clobTokenIds: List<String>? = null,
        @Query("include_tag") includeTag: Boolean? = null
    ): Response<List<MarketResponse>>

    @GET("/events/slug/{slug}")
    suspend fun getEventBySlug(@Path("slug") slug: String): Response<GammaEventBySlugResponse>

    @GET("/public-search")
    suspend fun publicSearch(
        @Query("q") query: String,
        @Query("limit_per_type") limitPerType: Int? = null,
        @Query("events_status") eventsStatus: String? = null,
        @Query("keep_closed_markets") keepClosedMarkets: Int? = null
    ): Response<GammaPublicSearchResponse>

    @GET("/events")
    suspend fun listEvents(
        @Query("active") active: Boolean? = null,
        @Query("closed") closed: Boolean? = null,
        @Query("limit") limit: Int? = null,
        @Query("offset") offset: Int? = null,
        @Query("tag_slug") tagSlug: String? = null
    ): Response<List<GammaSearchEventItem>>
}

data class GammaPublicSearchResponse(
    val events: List<GammaSearchEventItem>? = null
)

data class GammaSearchEventItem(
    val id: String? = null,
    val slug: String? = null,
    val title: String? = null,
    val category: String? = null,
    val active: Boolean? = null,
    val closed: Boolean? = null,
    val archived: Boolean? = null,
    val startDate: String? = null,
    val endDate: String? = null,
    val volume: Double? = null,
    val volumeClob: Double? = null,
    val liquidity: Double? = null,
    val liquidityClob: Double? = null,
    val openInterest: Double? = null,
    val volume24hr: Double? = null,
    val markets: List<GammaEventMarketItem>? = null
)

data class GammaEventBySlugResponse(
    val id: String? = null,
    val slug: String? = null,
    val title: String? = null,
    val startDate: String? = null,
    val endDate: String? = null,
    val volume: Double? = null,
    val volumeClob: Double? = null,
    val liquidity: Double? = null,
    val liquidityClob: Double? = null,
    val openInterest: Double? = null,
    val markets: List<GammaEventMarketItem>? = null
)

data class GammaEventMarketItem(
    val id: String? = null,
    val conditionId: String? = null,
    val slug: String? = null,
    val question: String? = null,
    val active: Boolean? = null,
    val closed: Boolean? = null,
    val endDate: String? = null,
    val startDate: String? = null,
    val clobTokenIds: String? = null,
    val clob_token_ids: String? = null,
    val outcomes: String? = null,
    val outcomePrices: String? = null,
    val volume: String? = null,
    val liquidity: String? = null,
    val volumeClob: Double? = null,
    val liquidityClob: Double? = null,
    val volumeNum: Double? = null,
    val liquidityNum: Double? = null,
    val lastTradePrice: Double? = null,
    val bestBid: Double? = null,
    val bestAsk: Double? = null,
    val sportsMarketType: String? = null,
    val marketType: String? = null,
    val line: String? = null,
    val groupItemTitle: String? = null
)

data class EventResponse(
    val id: String? = null,
    val ticker: String? = null,
    val slug: String? = null,
    val title: String? = null,
    val category: String? = null,
    val active: Boolean? = null,
    val closed: Boolean? = null,
    val archived: Boolean? = null,
    val startDate: String? = null,
    val endDate: String? = null,
    val createdAt: String? = null,
    val negRisk: Boolean? = null
)

data class MarketResponse(
    val id: String? = null,
    val question: String? = null,
    val conditionId: String? = null,
    val slug: String? = null,
    val icon: String? = null,
    val image: String? = null,
    val description: String? = null,
    val category: String? = null,
    val active: Boolean? = null,
    val closed: Boolean? = null,
    val archived: Boolean? = null,
    val volume: String? = null,
    val liquidity: String? = null,
    val endDate: String? = null,
    val startDate: String? = null,
    val outcomes: String? = null,
    val outcomePrices: String? = null,
    val volumeNum: Double? = null,
    val liquidityNum: Double? = null,
    val lastTradePrice: Double? = null,
    val bestBid: Double? = null,
    val bestAsk: Double? = null,
    val events: List<EventResponse>? = null,
    val clobTokenIds: String? = null,
    val clob_token_ids: String? = null,
    val negRisk: Boolean? = null,
    val negRiskOther: Boolean? = null
)

