package com.wrbug.polymarketbot.dto

import com.google.gson.annotations.SerializedName

data class ActivityTradeMessage(
    val topic: String = "",                    // "activity"
    val type: String = "",                     // "trades"
    val timestamp: Long? = null,
    @SerializedName("connection_id")
    val connectionId: String? = null,
    val payload: ActivityTradePayload = ActivityTradePayload()
)

/**
 * Activity Trade Payload
 */
data class ActivityTradePayload(
    val asset: String = "",
    
    @SerializedName("conditionId")
    val conditionId: String = "",              // Market condition ID
    
    @SerializedName("eventSlug")
    val eventSlug: String? = null,
    
    val slug: String? = null,
    
    val outcome: String? = null,
    
    @SerializedName("outcomeIndex")
    val outcomeIndex: Int? = null,
    
    val side: String = "",
    val price: Any? = null,
    
    val size: Any? = null,
    
    val timestamp: Any? = null,
    
    @SerializedName("transactionHash")
    val transactionHash: String? = null,
    
    val trader: ActivityTrader? = null,
    
    @SerializedName("proxyWallet")
    val proxyWallet: String? = null,
    
    val name: String? = null
)

data class ActivityTrader(
    val name: String? = null,
    val address: String? = null
)

