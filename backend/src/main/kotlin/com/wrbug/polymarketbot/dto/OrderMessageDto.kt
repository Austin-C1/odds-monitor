package com.wrbug.polymarketbot.dto

import com.fasterxml.jackson.annotation.JsonProperty

/**
 * Polymarket Order Message DTO
 */
data class OrderMessageDto(
    @JsonProperty("asset_id")
    val assetId: String,              // asset ID (token ID) of order
    
    @JsonProperty("associate_trades")
    val associateTrades: List<String>?, // array of ids referencing trades that the order has been included in
    
    @JsonProperty("event_type")
    val eventType: String,             // "order"
    
    val id: String,                    // order id
    val market: String,                // condition ID of market
    
    @JsonProperty("order_owner")
    val orderOwner: String,            // owner of order
    
    @JsonProperty("original_size")
    val originalSize: String,          // original order size
    val outcome: String,               // outcome
    val owner: String,                 // owner of orders
    val price: String,                 // price of order
    val side: String,                  // BUY/SELL
    
    @JsonProperty("size_matched")
    val sizeMatched: String,           // size of order that has been matched
    val timestamp: String,             // time of event
    val type: String                   // PLACEMENT/UPDATE/CANCELLATION
)

data class OrderPushMessage(
    val accountId: Long,
    val accountName: String,
    val order: OrderMessageDto,
    val orderDetail: OrderDetailDto? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val leaderName: String? = null,
    val configName: String? = null
)

data class OrderDetailDto(
    val id: String,
    val market: String,
    val side: String,                   // BUY/SELL
    val price: String,
    val size: String,
    val filled: String,
    val status: String,
    val createdAt: String,
    val marketName: String? = null,
    val marketSlug: String? = null,
    val marketIcon: String? = null,
    val avgFilledPrice: String? = null
)

