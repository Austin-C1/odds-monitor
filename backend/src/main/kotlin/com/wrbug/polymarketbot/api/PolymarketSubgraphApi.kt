package com.wrbug.polymarketbot.api

import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * Base URL: https://data-api.polymarket.com
 */
interface PolymarketDataApi {
    
    @GET("/positions")
    suspend fun getPositions(
        @Query("user") user: String,
        @Query("market") market: String? = null,
        @Query("eventId") eventId: String? = null,
        @Query("sizeThreshold") sizeThreshold: Double? = null,
        @Query("redeemable") redeemable: Boolean? = null,
        @Query("mergeable") mergeable: Boolean? = null,
        @Query("limit") limit: Int? = null,
        @Query("offset") offset: Int? = null,
        @Query("sortBy") sortBy: String? = null,
        @Query("sortDirection") sortDirection: String? = null,
        @Query("title") title: String? = null
    ): Response<List<PositionResponse>>
    
    @GET("/value")
    suspend fun getTotalValue(
        @Query("user") user: String,
        @Query("market") market: List<String>? = null
    ): Response<List<ValueResponse>>
    
    @GET("/activity")
    suspend fun getUserActivity(
        @Query("user") user: String,
        @Query("limit") limit: Int? = null,
        @Query("offset") offset: Int? = null,
        @Query("market") market: List<String>? = null,
        @Query("eventId") eventId: List<Int>? = null,
        @Query("type") type: List<String>? = null,
        @Query("start") start: Long? = null,
        @Query("end") end: Long? = null,
        @Query("sortBy") sortBy: String? = null,
        @Query("sortDirection") sortDirection: String? = null,
        @Query("side") side: String? = null
    ): Response<List<UserActivityResponse>>

    @GET("/holders")
    suspend fun getHolders(
        @Query("market") market: String,
        @Query("limit") limit: Int? = null,
        @Query("minBalance") minBalance: Int? = null
    ): Response<List<MarketHoldersResponse>>

    @GET("/trades")
    suspend fun getTrades(
        @Query("market") market: String,
        @Query("limit") limit: Int? = null,
        @Query("offset") offset: Int? = null,
        @Query("takerOnly") takerOnly: Boolean? = null
    ): Response<List<UserActivityResponse>>
}

data class MarketHoldersResponse(
    val token: String? = null,
    val holders: List<HolderResponse>? = null
)

data class HolderResponse(
    val proxyWallet: String? = null,
    val asset: String? = null,
    val pseudonym: String? = null,
    val name: String? = null,
    val amount: Double? = null,
    val outcomeIndex: Int? = null
)

data class PositionResponse(
    val proxyWallet: String,
    val asset: String? = null,
    val conditionId: String? = null,
    val size: Double? = null,
    val avgPrice: Double? = null,
    val initialValue: Double? = null,
    val currentValue: Double? = null,
    val cashPnl: Double? = null,
    val percentPnl: Double? = null,
    val totalBought: Double? = null,
    val realizedPnl: Double? = null,
    val percentRealizedPnl: Double? = null,
    val curPrice: Double? = null,
    val redeemable: Boolean? = null,
    val mergeable: Boolean? = null,
    val title: String? = null,
    val slug: String? = null,
    val icon: String? = null,
    val eventSlug: String? = null,
    val outcome: String? = null,
    val outcomeIndex: Int? = null,
    val oppositeOutcome: String? = null,
    val oppositeAsset: String? = null,
    val endDate: String? = null,
    val negativeRisk: Boolean? = null
)

data class ValueResponse(
    val user: String,
    val value: Double
)

data class UserActivityResponse(
    val proxyWallet: String,
    val timestamp: Long,
    val conditionId: String,
    val type: String,  // TRADE, SPLIT, MERGE, REDEEM, REWARD, CONVERSION
    val size: Double? = null,
    val usdcSize: Double? = null,
    val transactionHash: String? = null,
    val price: Double? = null,
    val asset: String? = null,
    val side: String? = null,  // BUY, SELL
    val outcomeIndex: Int? = null,
    val title: String? = null,
    val slug: String? = null,
    val icon: String? = null,
    val eventSlug: String? = null,
    val outcome: String? = null,
    val name: String? = null,
    val pseudonym: String? = null,
    val bio: String? = null,
    val profileImage: String? = null,
    val profileImageOptimized: String? = null
)


