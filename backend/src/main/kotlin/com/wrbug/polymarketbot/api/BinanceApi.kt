package com.wrbug.polymarketbot.api

import retrofit2.Call
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * Base URL: https://api.binance.com
 */
interface BinanceApi {

    @GET("/api/v3/klines")
    fun getKlines(
        @Query("symbol") symbol: String,
        @Query("interval") interval: String,
        @Query("limit") limit: Int = 30,
        @Query("startTime") startTime: Long? = null,
        @Query("endTime") endTime: Long? = null
    ): Call<List<List<Any>>>
}
