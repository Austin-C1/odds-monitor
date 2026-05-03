package com.wrbug.polymarketbot.api

import com.google.gson.annotations.SerializedName
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.*

interface PolymarketClobApi {
    
    @GET("/book")
    suspend fun getOrderbook(
        @Query("token_id") tokenId: String? = null,
        @Query("market") market: String? = null
    ): Response<OrderbookResponse>
    
    @GET("/price")
    suspend fun getPrice(
        @Query("token_id") tokenId: String? = null,
        @Query("side") side: String? = null,
        @Query("market") market: String? = null
    ): Response<PriceResponse>
    
    @GET("/midpoint")
    suspend fun getMidpoint(
        @Query("market") market: String
    ): Response<MidpointResponse>
    
    @GET("/spreads")
    suspend fun getSpreads(
        @Query("market") market: String
    ): Response<SpreadsResponse>
    
    /**
     * 
     * {
     *   "order": { signed order object },
     *   "owner": "api key",
     *   "orderType": "GTC" | "FOK" | "GTD" | "FAK"
     * }
     */
    @POST("/order")
    suspend fun createOrder(
        @Body request: NewOrderRequest
    ): Response<NewOrderResponse>
    
    @POST("/orders/batch")
    suspend fun createOrdersBatch(
        @Body request: CreateOrdersBatchRequest
    ): Response<List<OrderResponse>>
    
    @GET("/data/order/{orderId}")
    suspend fun getOrder(
        @Path("orderId") orderId: String
    ): Response<OpenOrder>
    
    @GET("/data/orders")
    suspend fun getActiveOrders(
        @Query("id") id: String? = null,
        @Query("market") market: String? = null,
        @Query("asset_id") asset_id: String? = null,
        @Query("next_cursor") next_cursor: String? = null
    ): Response<GetActiveOrdersResponse>
    
    @DELETE("/orders/{orderId}")
    suspend fun cancelOrder(
        @Path("orderId") orderId: String
    ): Response<CancelOrderResponse>
    
    @DELETE("/orders/batch")
    suspend fun cancelOrdersBatch(
        @Body request: CancelOrdersBatchRequest
    ): Response<CancelOrdersBatchResponse>
    
    @GET("/data/trades")
    suspend fun getTrades(
        @Query("id") id: String? = null,
        @Query("maker_address") maker_address: String? = null,
        @Query("market") market: String? = null,
        @Query("asset_id") asset_id: String? = null,
        @Query("before") before: String? = null,
        @Query("after") after: String? = null,
        @Query("next_cursor") next_cursor: String? = null
    ): Response<GetTradesResponse>
    
    @POST("/auth/api-key")
    suspend fun createApiKey(): Response<ApiKeyResponse>
    
    @GET("/auth/derive-api-key")
    suspend fun deriveApiKey(): Response<ApiKeyResponse>
    
    /**
     * 
     * @param tokenId Token ID
     */
    @GET("/fee-rate")
    suspend fun getFeeRate(
        @Query("token_id") tokenId: String
    ): Response<FeeRateResponse>
    
    @GET("/time")
    suspend fun getServerTime(): Response<ResponseBody>
}

data class SignedOrderObject(
    val salt: Long,                    // random salt used to create unique order
    val maker: String,                  // maker address (funder)
    val signer: String,                 // signing address
    val taker: String? = null,          // removed from V2 wire payload
    val tokenId: String,                // ERC1155 token ID of conditional token being traded
    val makerAmount: String,            // maximum amount maker is willing to spend
    val takerAmount: String,            // minimum amount taker will pay the maker in return
    val expiration: String? = null,     // removed from V2 wire payload
    val nonce: String? = null,          // removed from V2 signing payload
    val feeRateBps: String? = null,     // removed from V2 signing payload
    val side: String,                   // buy or sell enum index ("BUY" or "SELL")
    val signatureType: Int,             // signature type enum index
    val timestamp: String? = null,      // V2 order timestamp in milliseconds
    val metadata: String? = null,       // V2 order metadata bytes32
    val builder: String? = null,        // V2 builder code bytes32
    val signature: String               // hex encoded signature
)

data class NewOrderRequest(
    val order: SignedOrderObject,       // signed object
    val owner: String,                  // api key of order owner
    val orderType: String,              // order type ("FOK", "GTC", "GTD", "FAK")
    val deferExec: Boolean = false      // defer execution flag
)

data class NewOrderResponse(
    val success: Boolean,               // boolean indicating if server-side error
    @SerializedName("errorMsg")
    val errorMsg: String? = null,       // error message in case of unsuccessful placement
    val error: String? = null,          // error message (alternative field, e.g. "Trading restricted in your region...")
    @SerializedName("orderID")
    val orderId: String? = null,
    @SerializedName("transactionsHashes")
    val transactionsHashes: List<String>? = null,
    @SerializedName("status")
    val status: String? = null,         // order status (matched, pending, etc.)
    @SerializedName("takingAmount")
    val takingAmount: String? = null,   // taking amount
    @SerializedName("makingAmount")
    val makingAmount: String? = null    // making amount
) {
    fun getErrorMessage(): String {
        return errorMsg?.takeIf { it.isNotBlank() }
            ?: error?.takeIf { it.isNotBlank() }
            ?: "创建订单失败"
    }
}

@Deprecated("使用 NewOrderRequest 代替，需要签名的订单对象")
data class CreateOrderRequest(
    val market: String? = null,
    val token_id: String? = null,
    val side: String,                // "BUY" or "SELL"
    val price: String,
    val size: String,
    val type: String = "LIMIT",
    val expiration: Long? = null
)

@Deprecated("使用 NewOrderRequest 代替")
data class CreateOrdersBatchRequest(
    val orders: List<NewOrderRequest>
)

data class CancelOrdersBatchRequest(
    val orderIds: List<String>
)

data class OrderbookResponse(
    val bids: List<OrderbookEntry>,
    val asks: List<OrderbookEntry>
)

data class OrderbookEntry(
    val price: String,
    val size: String
)

data class PriceResponse(
    val market: String,
    val lastPrice: String?,
    val bestBid: String?,
    val bestAsk: String?
)

data class MidpointResponse(
    val market: String,
    val midpoint: String
)

data class SpreadsResponse(
    val market: String,
    val spread: String
)

data class OrderResponse(
    val id: String,
    val market: String,
    val side: String,
    val price: String,
    val size: String,
    val filled: String,
    val status: String,
    val createdAt: String
)

data class OpenOrder(
    val id: String,                              // order id
    val status: String,                          // order current status (LIVE, FILLED, CANCELLED, etc.)
    val owner: String,                           // api key
    @SerializedName("maker_address")
    val makerAddress: String,                    // maker address (funder)
    val market: String,                          // market id (condition id)
    @SerializedName("asset_id")
    val assetId: String,                         // token id
    val side: String,                            // BUY or SELL
    @SerializedName("original_size")
    val originalSize: String,                    // original order size at placement
    @SerializedName("size_matched")
    val sizeMatched: String,                     // size of order that has been matched/filled
    val price: String,                           // price
    val outcome: String,                         // human readable outcome the order is for
    val expiration: String,                       // unix timestamp when the order expired, 0 if it does not expire
    @SerializedName("order_type")
    val orderType: String,                        // order type (GTC, FOK, GTD)
    @SerializedName("associate_trades")
    val associateTrades: List<String>? = null,  // any Trade id the order has been partially included in
    @SerializedName("created_at")
    val createdAt: Long                          // unix timestamp when the order was created
)

data class CancelOrderResponse(
    val orderId: String,
    val status: String
)

data class CancelOrdersBatchResponse(
    val cancelled: List<String>,
    val failed: List<String>
)

data class TradeResponse(
    val id: String,
    val market: String,
    val side: String,
    val price: String,
    val size: String,
    val timestamp: String,
    val user: String?,
    val outcomeIndex: Int? = null,
    val outcome: String? = null,
    val tokenId: String? = null
)

data class GetActiveOrdersResponse(
    val data: List<OrderResponse>,
    val next_cursor: String? = null
)

data class GetTradesResponse(
    val data: List<TradeResponse>,
    val next_cursor: String? = null
)

data class ApiKeyResponse(
    val apiKey: String,
    val secret: String,
    val passphrase: String
)

data class FeeRateResponse(
    @SerializedName("base_fee")
    val baseFee: Int
)

data class LatestPriceResponse(
    val tokenId: String,
    val bestBid: String?,
    val bestAsk: String?
)

