package com.wrbug.polymarketbot.service.common

import com.wrbug.polymarketbot.api.PolymarketClobApi
import com.wrbug.polymarketbot.repository.AccountRepository
import com.wrbug.polymarketbot.util.CryptoUtils
import com.wrbug.polymarketbot.util.RetrofitFactory
import com.wrbug.polymarketbot.util.toSafeBigDecimal
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.BigInteger
import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine

@Service
class MarketPriceService(
    private val blockchainService: BlockchainService,
    private val retrofitFactory: RetrofitFactory,
    private val accountRepository: AccountRepository,
    private val cryptoUtils: CryptoUtils
) {
    
    private val logger = LoggerFactory.getLogger(MarketPriceService::class.java)
    
    /**
     * Key: "marketId:outcomeIndex"
     * 
     */
    private val settledMarketCache: Cache<String, BigDecimal> = Caffeine.newBuilder()
        .maximumSize(10_000)
        .recordStats()
        .build()
    
    suspend fun getCurrentMarketPrice(marketId: String, outcomeIndex: Int): BigDecimal {
        val (chainPrice, hasRpcError) = getPriceFromChainCondition(marketId, outcomeIndex)
        if (chainPrice != null) {
            return chainPrice.setScale(4, java.math.RoundingMode.DOWN)
        }
        if (hasRpcError) {
            logger.debug("链上查询市场条件出现 RPC 错误（execution reverted），降级到 API 查询: marketId=$marketId, outcomeIndex=$outcomeIndex")
        }
        val orderbookPrice = getPriceFromClobOrderbook(marketId, outcomeIndex)
        if (orderbookPrice != null) {
            return orderbookPrice.setScale(4, java.math.RoundingMode.DOWN)
        }
        val marketPrice = getPriceFromGammaMarket(marketId, outcomeIndex)
        if (marketPrice != null) {
            return marketPrice.setScale(4, java.math.RoundingMode.DOWN)
        }
        val errorMsg = "无法获取市场价格: marketId=$marketId, outcomeIndex=$outcomeIndex (链上查询、订单簿查询和 Market API 均失败)"
        logger.error(errorMsg)
        throw IllegalStateException(errorMsg)
    }
    
    private suspend fun getPriceFromChainCondition(marketId: String, outcomeIndex: Int): Pair<BigDecimal?, Boolean> {
        val cacheKey = "$marketId:$outcomeIndex"
        val cachedPrice = settledMarketCache.getIfPresent(cacheKey)
        if (cachedPrice != null) {
            logger.debug("从缓存获取已结算市场价格: marketId=$marketId, outcomeIndex=$outcomeIndex, price=$cachedPrice")
            return Pair(cachedPrice, false)
        }
        return try {
            val chainResult = blockchainService.getCondition(marketId)
            chainResult.fold(
                onSuccess = { (_, payouts) ->
                    if (payouts.isNotEmpty() && outcomeIndex < payouts.size) {
                        val payout = payouts[outcomeIndex]
                        when {
                            payout > BigInteger.ZERO -> {
                                logger.info("从链上查询到市场已结算，该 outcome 赢了: marketId=$marketId, outcomeIndex=$outcomeIndex, payout=$payout")
                                val price = BigDecimal.ONE
                                settledMarketCache.put(cacheKey, price)
                                return Pair(price, false)
                            }
                            payout == BigInteger.ZERO -> {
                                logger.info("从链上查询到市场已结算，该 outcome 输了: marketId=$marketId, outcomeIndex=$outcomeIndex, payout=$payout")
                                val price = BigDecimal.ZERO
                                settledMarketCache.put(cacheKey, price)
                                return Pair(price, false)
                            }
                            else -> {
                                logger.warn("从链上查询到异常的 payout 值: marketId=$marketId, outcomeIndex=$outcomeIndex, payout=$payout")
                                Pair(null, false)
                            }
                        }
                    } else {
                        logger.debug("从链上查询到市场尚未结算: marketId=$marketId, payouts=${payouts.size}")
                        Pair(null, false)
                    }
                },
                onFailure = { e ->
                    val isRpcError = e.message?.contains("execution reverted", ignoreCase = true) == true
                    if (isRpcError) {
                        logger.warn("链上查询市场条件出现 RPC 错误（execution reverted），可能市场不存在或尚未创建: marketId=$marketId, error=${e.message}")
                    } else {
                    logger.debug("链上查询市场条件失败，降级到 API 查询: marketId=$marketId, error=${e.message}")
                    }
                    Pair(null, isRpcError)
                }
            )
        } catch (e: Exception) {
            val isRpcError = e.message?.contains("execution reverted", ignoreCase = true) == true
            if (isRpcError) {
                logger.warn("链上查询市场条件异常（execution reverted）: marketId=$marketId, outcomeIndex=$outcomeIndex, error=${e.message}")
            } else {
            logger.debug("链上查询市场条件异常: marketId=$marketId, outcomeIndex=$outcomeIndex, error=${e.message}")
            }
            Pair(null, isRpcError)
        }
    }
    
    private suspend fun getPriceFromGammaMarket(marketId: String, outcomeIndex: Int): BigDecimal? {
        return try {
            val gammaApi = retrofitFactory.createGammaApi()
            val marketResponse = gammaApi.listMarkets(conditionIds = listOf(marketId))
            
            if (!marketResponse.isSuccessful || marketResponse.body() == null) {
                logger.debug("Gamma Market API 查询失败: marketId=$marketId, code=${marketResponse.code()}")
                return null
            }
            
            val markets = marketResponse.body()!!
            if (markets.isEmpty()) {
                logger.debug("Gamma Market API 未找到市场: marketId=$marketId")
                return null
            }
            
            val market = markets.first()
            val outcomePricesStr = market.outcomePrices
            if (outcomePricesStr.isNullOrBlank()) {
                logger.debug("Market outcomePrices 为空: marketId=$marketId")
                return null
            }
            val outcomePrices = try {
                val cleanStr = outcomePricesStr.trim().removeSurrounding("[", "]")
                cleanStr.split(",").map { 
                    it.trim().removeSurrounding("\"").toSafeBigDecimal() 
                }
            } catch (e: Exception) {
                logger.warn("解析 outcomePrices 失败: marketId=$marketId, outcomePrices=$outcomePricesStr, error=${e.message}")
                null
            }
            
            if (outcomePrices != null && outcomeIndex < outcomePrices.size) {
                val price = outcomePrices[outcomeIndex]
                logger.debug("从 Gamma Market API 获取价格: marketId=$marketId, outcomeIndex=$outcomeIndex, price=$price")
                return price
            }
            
            null
        } catch (e: Exception) {
            logger.debug("Gamma Market API 查询异常: marketId=$marketId, outcomeIndex=$outcomeIndex, error=${e.message}")
            null
        }
    }
    
    
    private suspend fun getPriceFromClobOrderbook(marketId: String, outcomeIndex: Int): BigDecimal? {
        return try {
            val tokenIdResult = blockchainService.getTokenId(marketId, outcomeIndex)
            if (!tokenIdResult.isSuccess) {
                return null
            }
            
            val tokenId = tokenIdResult.getOrNull() ?: return null
            val clobApi = try {
                getAuthenticatedClobApi() ?: retrofitFactory.createClobApiWithoutAuth()
            } catch (e: Exception) {
                logger.debug("获取带鉴权的 CLOB API 失败，使用不带鉴权的 API: ${e.message}")
                retrofitFactory.createClobApiWithoutAuth()
            }
            
            val orderbookResponse = clobApi.getOrderbook(tokenId = tokenId, market = null)
            
            if (!orderbookResponse.isSuccessful || orderbookResponse.body() == null) {
                return null
            }
            
            val orderbook = orderbookResponse.body()!!
            val bestBid = orderbook.bids
                .mapNotNull { it.price.toSafeBigDecimal() }
                .maxOrNull()
            val bestAsk = orderbook.asks
                .mapNotNull { it.price.toSafeBigDecimal() }
                .minOrNull()
            if (bestBid != null) {
                logger.debug("从订单簿获取价格（bestBid）: marketId=$marketId, outcomeIndex=$outcomeIndex, bestBid=$bestBid, bestAsk=$bestAsk")
                return bestBid
            } else if (bestAsk != null && bestAsk > BigDecimal.ZERO) {
                logger.debug("从订单簿获取价格（bestAsk）: marketId=$marketId, outcomeIndex=$outcomeIndex, bestAsk=$bestAsk")
                return bestAsk
            }
            
            null
        } catch (e: Exception) {
            logger.debug("CLOB API 查询订单簿失败: marketId=$marketId, outcomeIndex=$outcomeIndex, error=${e.message}")
            null
        }
    }
    
    private fun getAuthenticatedClobApi(): PolymarketClobApi? {
        return try {
            val account = accountRepository.findAllByOrderByCreatedAtAsc()
                .firstOrNull { it.apiKey != null && it.apiSecret != null && it.apiPassphrase != null }
            
            if (account == null || account.apiKey == null || account.apiSecret == null || account.apiPassphrase == null) {
                return null
            }
            val apiKey = account.apiKey
            val apiSecret = try {
                cryptoUtils.decrypt(account.apiSecret)
            } catch (e: Exception) {
                logger.debug("解密 API Secret 失败: ${e.message}")
                return null
            }
            val apiPassphrase = try {
                cryptoUtils.decrypt(account.apiPassphrase)
            } catch (e: Exception) {
                logger.debug("解密 API Passphrase 失败: ${e.message}")
                return null
            }
            retrofitFactory.createClobApi(apiKey, apiSecret, apiPassphrase, account.walletAddress)
        } catch (e: Exception) {
            logger.debug("获取带鉴权的 CLOB API 失败: ${e.message}")
            null
        }
    }
    
    fun getCacheStats(): String {
        val stats = settledMarketCache.stats()
        return """
            已结算市场缓存统计:
            - 缓存条目数: ${settledMarketCache.estimatedSize()}
            - 命中次数: ${stats.hitCount()}
            - 未命中次数: ${stats.missCount()}
            - 命中率: ${"%.2f".format(stats.hitRate() * 100)}%
            - 总请求次数: ${stats.requestCount()}
        """.trimIndent()
    }
    
    fun clearSettledMarketCache() {
        settledMarketCache.invalidateAll()
        logger.info("已清空已结算市场缓存")
    }
    
}

