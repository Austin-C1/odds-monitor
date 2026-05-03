package com.wrbug.polymarketbot.service.common

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import com.wrbug.polymarketbot.api.CancelOrderResponse
import com.wrbug.polymarketbot.api.CreateOrderRequest
import com.wrbug.polymarketbot.api.LatestPriceResponse
import com.wrbug.polymarketbot.api.MidpointResponse
import com.wrbug.polymarketbot.api.NewOrderRequest
import com.wrbug.polymarketbot.api.NewOrderResponse
import com.wrbug.polymarketbot.api.OpenOrder
import com.wrbug.polymarketbot.api.OrderResponse
import com.wrbug.polymarketbot.api.OrderbookResponse
import com.wrbug.polymarketbot.api.PolymarketClobApi
import com.wrbug.polymarketbot.api.PriceResponse
import com.wrbug.polymarketbot.api.TradeResponse
import com.wrbug.polymarketbot.api.GetActiveOrdersResponse
import com.wrbug.polymarketbot.api.GetTradesResponse
import com.wrbug.polymarketbot.util.RetrofitFactory
import com.wrbug.polymarketbot.util.toSafeBigDecimal
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.util.concurrent.TimeUnit

@Service
class PolymarketClobService(
    private val clobApi: PolymarketClobApi,
    private val retrofitFactory: RetrofitFactory
) {

    private val logger = LoggerFactory.getLogger(PolymarketClobService::class.java)
    private val fastTradingClobApi by lazy { retrofitFactory.createFastTradingClobApiWithoutAuth() }
    private val feeRateCache: Cache<String, Int> = Caffeine.newBuilder()
        .maximumSize(500)
        .expireAfterWrite(5, TimeUnit.MINUTES)
        .build()
    private val fastOrderbookCache: Cache<String, OrderbookResponse> = Caffeine.newBuilder()
        .maximumSize(1_000)
        .expireAfterWrite(2, TimeUnit.SECONDS)
        .build()

    private suspend fun requestOrderbook(
        api: PolymarketClobApi,
        tokenId: String?,
        market: String?
    ): Result<OrderbookResponse> {
        return try {
            val response = api.getOrderbook(tokenId = tokenId, market = market)
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                Result.failure(Exception("Failed to fetch orderbook: ${response.code()} ${response.message()}"))
            }
        } catch (e: Exception) {
            logger.error("Failed to fetch orderbook: ${e.message}", e)
            Result.failure(e)
        }
    }

    private suspend fun requestFeeRate(api: PolymarketClobApi, tokenId: String): Result<Int> {
        feeRateCache.getIfPresent(tokenId)?.let { return Result.success(it) }
        return try {
            val response = api.getFeeRate(tokenId)
            if (response.isSuccessful && response.body() != null) {
                val baseFee = response.body()!!.baseFee
                feeRateCache.put(tokenId, baseFee)
                logger.debug("Fetched fee rate: tokenId=$tokenId, baseFee=$baseFee")
                Result.success(baseFee)
            } else {
                val errorBody = try {
                    response.errorBody()?.string()
                } catch (_: Exception) {
                    null
                }
                logger.error("Failed to fetch fee rate: tokenId=$tokenId, code=${response.code()}, errorBody=$errorBody")
                Result.failure(Exception("Failed to fetch fee rate: ${response.code()} ${response.message()}"))
            }
        } catch (e: Exception) {
            logger.error("Failed to fetch fee rate: tokenId=$tokenId, error=${e.message}", e)
            Result.failure(e)
        }
    }

    suspend fun getOrderbook(market: String): Result<OrderbookResponse> {
        return requestOrderbook(clobApi, tokenId = null, market = market)
    }

    suspend fun getOrderbookByTokenId(tokenId: String): Result<OrderbookResponse> {
        return requestOrderbook(clobApi, tokenId = tokenId, market = null)
    }

    suspend fun getFastOrderbookByTokenId(tokenId: String): Result<OrderbookResponse> {
        fastOrderbookCache.getIfPresent(tokenId)?.let { return Result.success(it) }
        val result = requestOrderbook(fastTradingClobApi, tokenId = tokenId, market = null)
        result.getOrNull()?.let { fastOrderbookCache.put(tokenId, it) }
        return result
    }

    suspend fun getLatestPrice(tokenId: String): Result<LatestPriceResponse> {
        return try {
            val orderbookResult = getOrderbookByTokenId(tokenId)
            if (!orderbookResult.isSuccess) {
                val error = orderbookResult.exceptionOrNull()
                return Result.failure(Exception("Failed to get latest price: ${error?.message ?: "Unknown error"}"))
            }

            val orderbook = orderbookResult.getOrNull()
                ?: return Result.failure(IllegalStateException("Orderbook is empty: tokenId=$tokenId"))

            val bestBid = orderbook.bids.firstOrNull()?.price
            val bestBidPrice = bestBid?.toSafeBigDecimal()
            val bestAsk = orderbook.asks.firstOrNull()?.price
            val bestAskPrice = bestAsk?.toSafeBigDecimal()
            if (bestBidPrice != null && (bestBidPrice < BigDecimal("0.01") || bestBidPrice > BigDecimal("0.99"))) {
                logger.warn("Orderbook bestBid is out of range: $bestBid (tokenId=$tokenId)")
            }
            if (bestAskPrice != null && (bestAskPrice < BigDecimal("0.01") || bestAskPrice > BigDecimal("0.99"))) {
                logger.warn("Orderbook bestAsk is out of range: $bestAsk (tokenId=$tokenId)")
            }

            Result.success(
                LatestPriceResponse(
                    tokenId = tokenId,
                    bestBid = bestBid,
                    bestAsk = bestAsk
                )
            )
        } catch (e: Exception) {
            logger.error("Failed to get latest price: ${e.message}", e)
            Result.failure(e)
        }
    }

    suspend fun getOptimalPrice(
        tokenId: String,
        isSellOrder: Boolean,
        buyPriceAdjustment: BigDecimal = BigDecimal("0.01"),
        sellPriceAdjustment: BigDecimal = BigDecimal("0.02")
    ): String {
        val orderbookResult = getOrderbookByTokenId(tokenId)
        if (!orderbookResult.isSuccess) {
            val error = orderbookResult.exceptionOrNull()
            val errorMsg = "Failed to get optimal price: ${error?.message ?: "Unknown error"}"
            logger.error(errorMsg)
            throw IllegalStateException(errorMsg)
        }

        val orderbook = orderbookResult.getOrNull()
        if (orderbook == null) {
            val errorMsg = "Orderbook is empty: tokenId=$tokenId"
            logger.error(errorMsg)
            throw IllegalStateException(errorMsg)
        }

        if (isSellOrder) {
            val bestBid = orderbook.bids.firstOrNull()?.price
            if (bestBid == null) {
                val errorMsg = "Orderbook bids are empty: tokenId=$tokenId"
                logger.error(errorMsg)
                throw IllegalStateException(errorMsg)
            }

            val bestBidPrice = bestBid.toSafeBigDecimal()
            if (bestBidPrice < BigDecimal("0.01") || bestBidPrice > BigDecimal("0.99")) {
                val errorMsg = "Orderbook bestBid is out of range: $bestBid (tokenId=$tokenId)"
                logger.error(errorMsg)
                throw IllegalStateException(errorMsg)
            }
            val adjustedPrice = bestBidPrice.subtract(sellPriceAdjustment)
            val finalPrice = when {
                adjustedPrice < BigDecimal("0.01") -> BigDecimal("0.01")
                adjustedPrice > BigDecimal("0.99") -> BigDecimal("0.99")
                else -> adjustedPrice
            }
            return finalPrice.toPlainString()
        }

        val bestAsk = orderbook.asks.firstOrNull()?.price
        if (bestAsk == null) {
            val errorMsg = "Orderbook asks are empty: tokenId=$tokenId"
            logger.error(errorMsg)
            throw IllegalStateException(errorMsg)
        }

        val bestAskPrice = bestAsk.toSafeBigDecimal()
        if (bestAskPrice < BigDecimal("0.01") || bestAskPrice > BigDecimal("0.99")) {
            val errorMsg = "Orderbook bestAsk is out of range: $bestAsk (tokenId=$tokenId)"
            logger.error(errorMsg)
            throw IllegalStateException(errorMsg)
        }
        val adjustedPrice = bestAskPrice.add(buyPriceAdjustment)
        val finalPrice = when {
            adjustedPrice < BigDecimal("0.01") -> BigDecimal("0.01")
            adjustedPrice > BigDecimal("0.99") -> BigDecimal("0.99")
            else -> adjustedPrice
        }
        return finalPrice.toPlainString()
    }

    suspend fun getPrice(market: String): Result<PriceResponse> {
        return try {
            val response = clobApi.getPrice(market)
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                Result.failure(Exception("Failed to fetch price: ${response.code()} ${response.message()}"))
            }
        } catch (e: Exception) {
            logger.error("Failed to fetch price: ${e.message}", e)
            Result.failure(e)
        }
    }

    suspend fun getMidpoint(market: String): Result<MidpointResponse> {
        return try {
            val response = clobApi.getMidpoint(market)
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                Result.failure(Exception("Failed to fetch midpoint: ${response.code()} ${response.message()}"))
            }
        } catch (e: Exception) {
            logger.error("Failed to fetch midpoint: ${e.message}", e)
            Result.failure(e)
        }
    }

    @Deprecated("Use createSignedOrder instead")
    suspend fun createOrder(request: CreateOrderRequest): Result<OrderResponse> {
        return Result.failure(UnsupportedOperationException("createOrder is deprecated, use createSignedOrder instead"))
    }

    suspend fun createSignedOrder(request: NewOrderRequest): Result<NewOrderResponse> {
        return try {
            val response = clobApi.createOrder(request)
            if (response.isSuccessful && response.body() != null) {
                val responseBody = response.body()!!
                if (responseBody.success) {
                    Result.success(responseBody)
                } else {
                    val errorBody = try {
                        response.errorBody()?.string()
                    } catch (_: Exception) {
                        null
                    }
                    val errorMsg = "Failed to create signed order: orderType=${request.orderType}, owner=${request.owner}, errorMsg=${responseBody.errorMsg}${if (errorBody != null) ", errorBody=$errorBody" else ""}"
                    logger.error(errorMsg)
                    Result.failure(Exception(errorMsg))
                }
            } else {
                val errorBody = try {
                    response.errorBody()?.string()
                } catch (_: Exception) {
                    null
                }
                val errorMsg = "Failed to create signed order: orderType=${request.orderType}, owner=${request.owner}, code=${response.code()}, message=${response.message()}${if (errorBody != null) ", errorBody=$errorBody" else ""}"
                logger.error(errorMsg)
                Result.failure(Exception(errorMsg))
            }
        } catch (e: Exception) {
            val errorMsg = "Failed to create signed order: orderType=${request.orderType}, owner=${request.owner}, error=${e.message}"
            logger.error(errorMsg, e)
            Result.failure(Exception(errorMsg, e))
        }
    }

    suspend fun getOrder(
        orderId: String,
        apiKey: String,
        apiSecret: String,
        apiPassphrase: String,
        walletAddress: String
    ): Result<OpenOrder> {
        return try {
            val authenticatedClobApi = retrofitFactory.createClobApi(
                apiKey = apiKey,
                apiSecret = apiSecret,
                apiPassphrase = apiPassphrase,
                walletAddress = walletAddress
            )

            val response = authenticatedClobApi.getOrder(orderId)
            if (response.isSuccessful) {
                val body = response.body()
                if (body != null) {
                    Result.success(body)
                } else {
                    logger.warn("Failed to fetch order detail: empty response body, orderId=$orderId, code=${response.code()}")
                    Result.failure(Exception("Order detail response is empty: orderId=$orderId"))
                }
            } else {
                val errorBody = response.errorBody()?.string()?.take(200) ?: "no_error_body"
                logger.warn("Failed to fetch order detail: HTTP ${response.code()}, orderId=$orderId, errorBody=$errorBody")
                Result.failure(Exception("Failed to fetch order detail: HTTP ${response.code()} ${response.message()}"))
            }
        } catch (e: Exception) {
            logger.error("Failed to fetch order detail: orderId=$orderId, ${e.message}", e)
            Result.failure(e)
        }
    }

    suspend fun getActiveOrders(
        id: String? = null,
        market: String? = null,
        asset_id: String? = null,
        next_cursor: String? = null
    ): Result<List<OrderResponse>> {
        return try {
            val response = clobApi.getActiveOrders(
                id = id,
                market = market,
                asset_id = asset_id,
                next_cursor = next_cursor
            )
            if (response.isSuccessful && response.body() != null) {
                val ordersResponse: GetActiveOrdersResponse = response.body()!!
                Result.success(ordersResponse.data)
            } else {
                Result.failure(Exception("Failed to fetch active orders: ${response.code()} ${response.message()}"))
            }
        } catch (e: Exception) {
            logger.error("Failed to fetch active orders: ${e.message}", e)
            Result.failure(e)
        }
    }

    suspend fun cancelOrder(orderId: String): Result<CancelOrderResponse> {
        return try {
            val response = clobApi.cancelOrder(orderId)
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                Result.failure(Exception("Failed to cancel order: ${response.code()} ${response.message()}"))
            }
        } catch (e: Exception) {
            logger.error("Failed to cancel order: ${e.message}", e)
            Result.failure(e)
        }
    }

    suspend fun getTrades(
        id: String? = null,
        maker_address: String? = null,
        market: String? = null,
        asset_id: String? = null,
        before: String? = null,
        after: String? = null,
        next_cursor: String? = null,
        limit: Int? = null
    ): Result<List<TradeResponse>> {
        return try {
            val response: retrofit2.Response<GetTradesResponse> = clobApi.getTrades(
                id = id,
                maker_address = maker_address,
                market = market,
                asset_id = asset_id,
                before = before,
                after = after,
                next_cursor = next_cursor
            )
            if (response.isSuccessful && response.body() != null) {
                val tradesResponse = response.body()!!
                Result.success(tradesResponse.data)
            } else {
                Result.failure(Exception("Failed to fetch trades: ${response.code()} ${response.message()}"))
            }
        } catch (e: Exception) {
            logger.error("Failed to fetch trades: ${e.message}", e)
            Result.failure(e)
        }
    }

    suspend fun getFeeRate(tokenId: String): Result<Int> {
        return requestFeeRate(clobApi, tokenId)
    }

    suspend fun getFastFeeRate(tokenId: String): Result<Int> {
        return requestFeeRate(fastTradingClobApi, tokenId)
    }
}
