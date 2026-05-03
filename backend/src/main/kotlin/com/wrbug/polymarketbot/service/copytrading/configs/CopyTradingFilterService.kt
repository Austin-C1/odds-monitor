package com.wrbug.polymarketbot.service.copytrading.configs

import com.wrbug.polymarketbot.api.OrderbookResponse
import com.wrbug.polymarketbot.dto.AccountPositionDto
import com.wrbug.polymarketbot.entity.CopyTrading
import com.wrbug.polymarketbot.repository.CopyOrderTrackingRepository
import com.wrbug.polymarketbot.service.accounts.AccountService
import com.wrbug.polymarketbot.service.common.PolymarketClobService
import com.wrbug.polymarketbot.util.DateUtils
import com.wrbug.polymarketbot.util.JsonUtils
import com.wrbug.polymarketbot.util.gt
import com.wrbug.polymarketbot.util.lt
import com.wrbug.polymarketbot.util.multi
import com.wrbug.polymarketbot.util.toSafeBigDecimal
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.math.BigDecimal

@Service
class CopyTradingFilterService(
    private val clobService: PolymarketClobService,
    private val accountService: AccountService,
    private val copyOrderTrackingRepository: CopyOrderTrackingRepository,
    private val jsonUtils: JsonUtils
) {

    private val logger = LoggerFactory.getLogger(CopyTradingFilterService::class.java)

    suspend fun checkFilters(
        copyTrading: CopyTrading,
        tokenId: String,
        tradePrice: BigDecimal? = null,
        copyOrderAmount: BigDecimal? = null,
        marketId: String? = null,
        marketTitle: String? = null,
        marketEndDate: Long? = null,
        outcomeIndex: Int? = null,
        sharedOrderbook: OrderbookResponse? = null,
        currentPositions: List<AccountPositionDto>? = null
    ): FilterResult {
        if (copyTrading.keywordFilterMode != "DISABLED") {
            val keywordCheck = checkKeywordFilter(copyTrading, marketTitle)
            if (!keywordCheck.isPassed) {
                return keywordCheck
            }
        }
        if (copyTrading.maxMarketEndDate != null) {
            val marketEndDateCheck = checkMarketEndDate(copyTrading, marketEndDate)
            if (!marketEndDateCheck.isPassed) {
                return marketEndDateCheck
            }
        }
        if (tradePrice != null) {
            val priceRangeCheck = checkPriceRange(copyTrading, tradePrice)
            if (!priceRangeCheck.isPassed) {
                return FilterResult.priceRangeFailed(priceRangeCheck.reason)
            }
        }

        val needOrderbook = copyTrading.maxSpread != null || copyTrading.minOrderDepth != null
        if (!needOrderbook) {
            if (copyOrderAmount != null && marketId != null) {
                val positionCheck = checkPositionLimits(copyTrading, copyOrderAmount, marketId, outcomeIndex, currentPositions)
                if (!positionCheck.isPassed) {
                    return positionCheck
                }
            }
            return FilterResult.passed()
        }

        val orderbook = sharedOrderbook ?: run {
            val orderbookResult = clobService.getFastOrderbookByTokenId(tokenId)
            if (!orderbookResult.isSuccess) {
                val error = orderbookResult.exceptionOrNull()
                return FilterResult.orderbookError("Failed to fetch orderbook: ${error?.message ?: "Unknown error"}")
            }
            orderbookResult.getOrNull() ?: return FilterResult.orderbookEmpty()
        }

        if (copyTrading.maxSpread != null) {
            val spreadCheck = checkSpread(copyTrading, orderbook)
            if (!spreadCheck.isPassed) {
                return FilterResult.spreadFailed(spreadCheck.reason, orderbook)
            }
        }
        if (copyTrading.minOrderDepth != null) {
            val depthCheck = checkOrderDepth(copyTrading, orderbook)
            if (!depthCheck.isPassed) {
                return FilterResult.orderDepthFailed(depthCheck.reason, orderbook)
            }
        }
        if (copyOrderAmount != null && marketId != null) {
            val positionCheck = checkPositionLimits(copyTrading, copyOrderAmount, marketId, outcomeIndex, currentPositions)
            if (!positionCheck.isPassed) {
                return positionCheck
            }
        }

        return FilterResult.passed(orderbook)
    }

    private fun checkKeywordFilter(
        copyTrading: CopyTrading,
        marketTitle: String?
    ): FilterResult {
        if (copyTrading.keywordFilterMode == "DISABLED") {
            return FilterResult.passed()
        }
        if (marketTitle.isNullOrBlank()) {
            return FilterResult.keywordFilterFailed("Market title is missing, keyword filter cannot be applied")
        }
        val keywords = jsonUtils.parseStringArray(copyTrading.keywords)
        if (keywords.isEmpty()) {
            return if (copyTrading.keywordFilterMode == "WHITELIST") {
                FilterResult.keywordFilterFailed("Whitelist mode requires at least one keyword")
            } else {
                FilterResult.passed()
            }
        }

        val titleLower = marketTitle.lowercase()
        val containsKeyword = keywords.any { keyword ->
            titleLower.contains(keyword.lowercase())
        }
        return when (copyTrading.keywordFilterMode) {
            "WHITELIST" -> {
                if (containsKeyword) {
                    FilterResult.passed()
                } else {
                    FilterResult.keywordFilterFailed("Whitelist keywords did not match market title")
                }
            }

            "BLACKLIST" -> {
                if (containsKeyword) {
                    FilterResult.keywordFilterFailed("Blacklist keyword matched market title")
                } else {
                    FilterResult.passed()
                }
            }

            else -> FilterResult.passed()
        }
    }

    private fun checkPriceRange(
        copyTrading: CopyTrading,
        tradePrice: BigDecimal
    ): FilterResult {
        if (copyTrading.minPrice == null && copyTrading.maxPrice == null) {
            return FilterResult.passed()
        }
        if (copyTrading.minPrice != null && tradePrice.lt(copyTrading.minPrice)) {
            val priceStr = tradePrice.stripTrailingZeros().toPlainString()
            val minPriceStr = copyTrading.minPrice.stripTrailingZeros().toPlainString()
            return FilterResult.priceRangeFailed("Trade price is below the minimum limit: $priceStr < $minPriceStr")
        }
        if (copyTrading.maxPrice != null && tradePrice.gt(copyTrading.maxPrice)) {
            val priceStr = tradePrice.stripTrailingZeros().toPlainString()
            val maxPriceStr = copyTrading.maxPrice.stripTrailingZeros().toPlainString()
            return FilterResult.priceRangeFailed("Trade price is above the maximum limit: $priceStr > $maxPriceStr")
        }

        return FilterResult.passed()
    }

    private fun checkSpread(
        copyTrading: CopyTrading,
        orderbook: OrderbookResponse
    ): FilterResult {
        if (copyTrading.maxSpread == null) {
            return FilterResult.passed()
        }
        val bestBid = orderbook.bids.mapNotNull { it.price.toSafeBigDecimal() }.maxOrNull()
        val bestAsk = orderbook.asks.mapNotNull { it.price.toSafeBigDecimal() }.minOrNull()

        if (bestBid == null || bestAsk == null) {
            return FilterResult.spreadFailed("Orderbook is missing best bid or best ask", orderbook)
        }
        val spread = bestAsk.subtract(bestBid)
        if (spread.gt(copyTrading.maxSpread)) {
            val spreadStr = spread.stripTrailingZeros().toPlainString()
            val maxSpreadStr = copyTrading.maxSpread.stripTrailingZeros().toPlainString()
            return FilterResult.spreadFailed("Spread exceeds the maximum limit: $spreadStr > $maxSpreadStr", orderbook)
        }

        return FilterResult.passed()
    }

    private fun checkOrderDepth(
        copyTrading: CopyTrading,
        orderbook: OrderbookResponse
    ): FilterResult {
        if (copyTrading.minOrderDepth == null) {
            return FilterResult.passed()
        }
        var bidsDepth = BigDecimal.ZERO
        for (order in orderbook.bids) {
            val price = order.price.toSafeBigDecimal()
            val size = order.size.toSafeBigDecimal()
            bidsDepth = bidsDepth.add(price.multi(size))
        }
        var asksDepth = BigDecimal.ZERO
        for (order in orderbook.asks) {
            val price = order.price.toSafeBigDecimal()
            val size = order.size.toSafeBigDecimal()
            asksDepth = asksDepth.add(price.multi(size))
        }
        val totalDepth = bidsDepth.add(asksDepth)
        if (totalDepth.lt(copyTrading.minOrderDepth)) {
            val totalDepthStr = totalDepth.stripTrailingZeros().toPlainString()
            val minDepthStr = copyTrading.minOrderDepth.stripTrailingZeros().toPlainString()
            return FilterResult.orderDepthFailed("Orderbook depth is below the minimum limit: $totalDepthStr < $minDepthStr", orderbook)
        }

        return FilterResult.passed()
    }

    private suspend fun checkPositionLimits(
        copyTrading: CopyTrading,
        copyOrderAmount: BigDecimal,
        marketId: String,
        outcomeIndex: Int?,
        currentPositions: List<AccountPositionDto>? = null
    ): FilterResult {
        val maxPositionValue = copyTrading.maxPositionValue ?: return FilterResult.passed()
        if (outcomeIndex == null) {
            return FilterResult.passed()
        }

        try {
            val positions = if (currentPositions != null) {
                currentPositions
            } else {
                val positionsResult = accountService.getCurrentPositionsForAccount(copyTrading.accountId)
                if (positionsResult.isFailure) {
                    logger.warn("Failed to load positions for position limit check: accountId=${copyTrading.accountId}, marketId=$marketId, outcomeIndex=$outcomeIndex, error=${positionsResult.exceptionOrNull()?.message}")
                    return FilterResult.maxPositionValueFailed("Failed to load positions for position limit check")
                }
                positionsResult.getOrNull() ?: return FilterResult.maxPositionValueFailed("Position list is empty")
            }

            val marketPositions = positions.filter {
                it.accountId == copyTrading.accountId && it.marketId == marketId
            }
            val dbValue = copyOrderTrackingRepository.sumCurrentPositionValueByMarketAndOutcomeIndex(
                copyTrading.id!!,
                marketId,
                outcomeIndex
            ) ?: BigDecimal.ZERO
            val extValue = if (marketPositions.isNotEmpty()) {
                marketPositions.sumOf { it.currentValue.toSafeBigDecimal() }
            } else {
                BigDecimal.ZERO
            }
            val currentPositionValue = dbValue.max(extValue)
            val totalValueAfterOrder = currentPositionValue.add(copyOrderAmount)

            if (totalValueAfterOrder.gt(maxPositionValue)) {
                val currentValueStr = currentPositionValue.stripTrailingZeros().toPlainString()
                val dbValueStr = dbValue.stripTrailingZeros().toPlainString()
                val extValueStr = extValue.stripTrailingZeros().toPlainString()
                val orderAmountStr = copyOrderAmount.stripTrailingZeros().toPlainString()
                val totalValueStr = totalValueAfterOrder.stripTrailingZeros().toPlainString()
                val maxValueStr = maxPositionValue.stripTrailingZeros().toPlainString()
                return FilterResult.maxPositionValueFailed(
                    "Position limit exceeded: marketId=$marketId, outcomeIndex=$outcomeIndex, currentValue=${currentValueStr} USDC (DB=${dbValueStr}, Ext=${extValueStr}), orderAmount=${orderAmountStr} USDC, totalValue=${totalValueStr} USDC > maxValue=${maxValueStr} USDC"
                )
            }

            return FilterResult.passed()
        } catch (e: Exception) {
            logger.error(
                "Position check failed: accountId={}, marketId={}, outcomeIndex={}, error={}",
                copyTrading.accountId,
                marketId,
                outcomeIndex,
                e.message,
                e
            )
            return FilterResult.maxPositionValueFailed("Position check failed: ${e.message}")
        }
    }

    private fun checkMarketEndDate(
        copyTrading: CopyTrading,
        marketEndDate: Long?
    ): FilterResult {
        if (copyTrading.maxMarketEndDate == null) {
            return FilterResult.passed()
        }
        if (marketEndDate == null) {
            return FilterResult.marketEndDateFailed("Market end time is required for this filter")
        }
        val currentTime = System.currentTimeMillis()
        val remainingTime = marketEndDate - currentTime
        if (remainingTime > copyTrading.maxMarketEndDate) {
            val remainingTimeFormatted = DateUtils.formatDuration(remainingTime)
            val maxLimitFormatted = DateUtils.formatDuration(copyTrading.maxMarketEndDate)
            return FilterResult.marketEndDateFailed(
                "Market end time exceeds the maximum limit: $remainingTimeFormatted > $maxLimitFormatted"
            )
        }

        return FilterResult.passed()
    }
}
