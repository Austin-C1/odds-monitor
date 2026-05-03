package com.wrbug.polymarketbot.service.common

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import com.wrbug.polymarketbot.api.MarketResponse
import com.wrbug.polymarketbot.api.PolymarketGammaApi
import com.wrbug.polymarketbot.entity.Market
import com.wrbug.polymarketbot.repository.MarketRepository
import com.wrbug.polymarketbot.util.RetrofitFactory
import com.wrbug.polymarketbot.util.getEventSlug
import com.wrbug.polymarketbot.util.parseStringArray
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

@Service
class MarketService(
    val marketRepository: MarketRepository,
    private val retrofitFactory: RetrofitFactory
) {

    private val logger = LoggerFactory.getLogger(MarketService::class.java)
    private val marketInfoByTokenCache: Cache<String, MarketInfoByTokenId> = Caffeine.newBuilder()
        .maximumSize(500)
        .expireAfterWrite(5, TimeUnit.MINUTES)
        .build()
    private val negRiskCache: Cache<String, Boolean> = Caffeine.newBuilder()
        .maximumSize(500)
        .expireAfterWrite(5, TimeUnit.MINUTES)
        .build()
    private val marketCache: Cache<String, Market> = Caffeine.newBuilder()
        .maximumSize(200)
        .build()
    
    fun getMarket(marketId: String): Market? {
        marketCache.getIfPresent(marketId)?.let { return it }
        val market = marketRepository.findByMarketId(marketId)
        if (market != null) {
            marketCache.put(marketId, market)
            return market
        }
        runBlocking {
            try {
                fetchAndSaveMarket(marketId)
            } catch (e: Exception) {
                logger.warn("获取市场信息失败: marketId=$marketId, error=${e.message}")
            }
        }
        return marketRepository.findByMarketId(marketId)?.also {
            marketCache.put(marketId, it)
        }
    }
    
    fun getMarkets(marketIds: List<String>): Map<String, Market> {
        val result = mutableMapOf<String, Market>()
        val missingIds = mutableListOf<String>()
        for (marketId in marketIds) {
            val market = getMarket(marketId)
            if (market != null) {
                result[marketId] = market
            } else {
                missingIds.add(marketId)
            }
        }
        if (missingIds.isNotEmpty()) {
            runBlocking {
                try {
                    fetchAndSaveMarkets(missingIds)
                } catch (e: Exception) {
                    logger.warn("批量获取市场信息失败: marketIds=$missingIds, error=${e.message}")
                }
            }
            val savedMarkets = marketRepository.findByMarketIdIn(missingIds)
            for (market in savedMarkets) {
                result[market.marketId] = market
                marketCache.put(market.marketId, market)
            }
        }
        
        return result
    }
    
    private suspend fun fetchAndSaveMarket(marketId: String): Market? {
        return try {
            val gammaApi = retrofitFactory.createGammaApi()
            val response = gammaApi.listMarkets(conditionIds = listOf(marketId))
            
            if (response.isSuccessful && response.body() != null) {
                val markets = response.body()!!
                if (markets.isNotEmpty()) {
                    val marketResponse = markets.first()
                    saveMarketFromResponse(marketId, marketResponse)
                } else {
                    null
                }
            } else {
                null
            }
        } catch (e: Exception) {
            logger.error("从API获取市场信息失败: marketId=$marketId, error=${e.message}", e)
            null
        }
    }
    
    private suspend fun fetchAndSaveMarkets(marketIds: List<String>) {
        if (marketIds.isEmpty()) return
        
        try {
            val gammaApi = retrofitFactory.createGammaApi()
            val response = gammaApi.listMarkets(conditionIds = marketIds)
            
            if (response.isSuccessful && response.body() != null) {
                val markets = response.body()!!
                val marketMap = markets.associateBy { it.conditionId ?: "" }
                
                for (marketId in marketIds) {
                    val marketResponse = marketMap[marketId]
                    if (marketResponse != null) {
                        saveMarketFromResponse(marketId, marketResponse)
                    }
                }
            }
        } catch (e: Exception) {
            logger.error("批量从API获取市场信息失败: marketIds=$marketIds, error=${e.message}", e)
        }
    }
    
    private fun saveMarketFromResponse(marketId: String, marketResponse: MarketResponse): Market? {
        return try {
            val existingMarket = marketRepository.findByMarketId(marketId)
            val slug = marketResponse.slug
            val eventSlug = marketResponse.getEventSlug()
            
            val market = if (existingMarket != null) {
                existingMarket.copy(
                    title = marketResponse.question ?: existingMarket.title,
                    slug = slug ?: existingMarket.slug,
                    eventSlug = eventSlug ?: existingMarket.eventSlug,
                    category = marketResponse.category ?: existingMarket.category,
                    icon = marketResponse.icon ?: existingMarket.icon,
                    image = marketResponse.image ?: existingMarket.image,
                    description = marketResponse.description ?: existingMarket.description,
                    active = marketResponse.active ?: existingMarket.active,
                    closed = marketResponse.closed ?: existingMarket.closed,
                    archived = marketResponse.archived ?: existingMarket.archived,
                    endDate = parseEndDate(marketResponse.endDate),
                    updatedAt = System.currentTimeMillis()
                )
            } else {
                Market(
                    marketId = marketId,
                    title = marketResponse.question ?: marketId,
                    slug = slug,
                    eventSlug = eventSlug,
                    category = marketResponse.category,
                    icon = marketResponse.icon,
                    image = marketResponse.image,
                    description = marketResponse.description,
                    active = marketResponse.active ?: true,
                    closed = marketResponse.closed ?: false,
                    archived = marketResponse.archived ?: false,
                    endDate = parseEndDate(marketResponse.endDate),
                    createdAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis()
                )
            }
            
            val savedMarket = marketRepository.save(market)
            marketCache.put(marketId, savedMarket)
            savedMarket
        } catch (e: Exception) {
            logger.error("保存市场信息失败: marketId=$marketId, error=${e.message}", e)
            null
        }
    }
    
    suspend fun getMarketInfoByTokenId(tokenId: String): MarketInfoByTokenId? {
        if (tokenId.isBlank()) return null
        marketInfoByTokenCache.getIfPresent(tokenId)?.let { return it }
        return try {
            val gammaApi = retrofitFactory.createGammaApi()
            val response = gammaApi.listMarkets(
                conditionIds = null,
                clobTokenIds = listOf(tokenId),
                includeTag = null
            )
            if (!response.isSuccessful || response.body().isNullOrEmpty()) return null
            val market = response.body()!!.first()
            val conditionId = market.conditionId ?: return null
            val clobTokenIdsRaw = market.clobTokenIds ?: market.clob_token_ids
            val clobTokenIds = (clobTokenIdsRaw ?: "").parseStringArray()
            val outcomeIndex = clobTokenIds.indexOfFirst { it.equals(tokenId, ignoreCase = true) }.takeIf { it >= 0 }
                ?: return null
            val outcomes = market.outcomes.parseStringArray()
            val outcome = if (outcomeIndex < outcomes.size) outcomes[outcomeIndex] else null
            saveMarketFromResponse(conditionId, market)
            MarketInfoByTokenId(conditionId = conditionId, outcomeIndex = outcomeIndex, outcome = outcome).also {
                marketInfoByTokenCache.put(tokenId, it)
            }
        } catch (e: Exception) {
            logger.warn("按 tokenId 查询市场失败: tokenId=$tokenId, error=${e.message}")
            null
        }
    }

    fun clearCache() {
        marketCache.invalidateAll()
    }
    
    private fun parseEndDate(endDate: String?): Long? {
        if (endDate.isNullOrBlank()) {
            return null
        }
        
        return try {
            Instant.parse(endDate).toEpochMilli()
        } catch (e: Exception) {
            logger.warn("解析市场截止时间失败: endDate=$endDate, error=${e.message}")
            null
        }
    }

    suspend fun getNegRiskByConditionId(conditionId: String): Boolean? {
        if (conditionId.isBlank()) return null
        negRiskCache.getIfPresent(conditionId)?.let { return it }
        return try {
            val gammaApi = retrofitFactory.createGammaApi()
            val response = gammaApi.listMarkets(conditionIds = listOf(conditionId))
            if (!response.isSuccessful || response.body().isNullOrEmpty()) return null
            val marketResponse = response.body()!!.first()
            val fromEvent = marketResponse.events?.firstOrNull()?.negRisk
            val fromMarket = marketResponse.negRisk ?: marketResponse.negRiskOther
            (fromEvent ?: fromMarket)?.also { negRiskCache.put(conditionId, it) }
        } catch (e: Exception) {
            logger.warn("查询市场 negRisk 失败: conditionId=$conditionId, error=${e.message}")
            null
        }
    }
}

data class MarketInfoByTokenId(
    val conditionId: String,
    val outcomeIndex: Int,
    val outcome: String? = null
)
