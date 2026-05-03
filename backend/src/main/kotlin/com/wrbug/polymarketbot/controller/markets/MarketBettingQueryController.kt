package com.wrbug.polymarketbot.controller.markets

import com.wrbug.polymarketbot.dto.ApiResponse
import com.wrbug.polymarketbot.dto.MarketBettingDetailRequest
import com.wrbug.polymarketbot.dto.MarketBettingEventDetail
import com.wrbug.polymarketbot.dto.MarketBettingSearchRequest
import com.wrbug.polymarketbot.dto.MarketBettingSearchResponse
import com.wrbug.polymarketbot.enums.ErrorCode
import com.wrbug.polymarketbot.service.market.MarketBettingQueryService
import org.slf4j.LoggerFactory
import org.springframework.context.MessageSource
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/market-betting-query")
class MarketBettingQueryController(
    private val marketBettingQueryService: MarketBettingQueryService,
    private val messageSource: MessageSource
) {
    private val logger = LoggerFactory.getLogger(MarketBettingQueryController::class.java)

    @PostMapping("/search")
    suspend fun search(@RequestBody request: MarketBettingSearchRequest): ResponseEntity<ApiResponse<MarketBettingSearchResponse>> {
        return marketBettingQueryService.search(request.query, request.limit ?: 5, request.date).fold(
            onSuccess = { ResponseEntity.ok(ApiResponse.success(it)) },
            onFailure = { error ->
                logger.warn("盘口投注额搜索失败: {}", error.message)
                ResponseEntity.ok(ApiResponse.error(ErrorCode.PARAM_ERROR, error.message, messageSource))
            }
        )
    }

    @PostMapping("/detail")
    suspend fun detail(@RequestBody request: MarketBettingDetailRequest): ResponseEntity<ApiResponse<MarketBettingEventDetail>> {
        return marketBettingQueryService.detail(
            query = request.query,
            slug = request.slug,
            marketLimit = request.marketLimit ?: 30,
            date = request.date
        ).fold(
            onSuccess = { ResponseEntity.ok(ApiResponse.success(it)) },
            onFailure = { error ->
                logger.warn("盘口投注额详情失败: {}", error.message)
                ResponseEntity.ok(ApiResponse.error(ErrorCode.PARAM_ERROR, error.message, messageSource))
            }
        )
    }
}
