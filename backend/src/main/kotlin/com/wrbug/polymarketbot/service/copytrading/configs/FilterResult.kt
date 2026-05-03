package com.wrbug.polymarketbot.service.copytrading.configs

import com.wrbug.polymarketbot.api.OrderbookResponse

enum class FilterStatus {
    PASSED,
    FAILED_PRICE_RANGE,
    FAILED_ORDERBOOK_ERROR,
    FAILED_ORDERBOOK_EMPTY,
    FAILED_SPREAD,
    FAILED_ORDER_DEPTH,
    FAILED_MAX_POSITION_VALUE,
    FAILED_KEYWORD_FILTER,
    FAILED_MARKET_END_DATE
}

data class FilterResult(
    val status: FilterStatus,
    val reason: String = "",
    val orderbook: OrderbookResponse? = null
) {
    val isPassed: Boolean
        get() = status == FilterStatus.PASSED

    companion object {
        fun passed(orderbook: OrderbookResponse? = null) = FilterResult(
            status = FilterStatus.PASSED,
            orderbook = orderbook
        )

        fun priceRangeFailed(reason: String) = FilterResult(
            status = FilterStatus.FAILED_PRICE_RANGE,
            reason = reason
        )

        fun orderbookError(reason: String) = FilterResult(
            status = FilterStatus.FAILED_ORDERBOOK_ERROR,
            reason = reason
        )

        fun orderbookEmpty() = FilterResult(
            status = FilterStatus.FAILED_ORDERBOOK_EMPTY,
            reason = "订单簿为空"
        )

        fun spreadFailed(reason: String, orderbook: OrderbookResponse) = FilterResult(
            status = FilterStatus.FAILED_SPREAD,
            reason = reason,
            orderbook = orderbook
        )

        fun orderDepthFailed(reason: String, orderbook: OrderbookResponse) = FilterResult(
            status = FilterStatus.FAILED_ORDER_DEPTH,
            reason = reason,
            orderbook = orderbook
        )

        fun maxPositionValueFailed(reason: String) = FilterResult(
            status = FilterStatus.FAILED_MAX_POSITION_VALUE,
            reason = reason
        )
        
        fun keywordFilterFailed(reason: String) = FilterResult(
            status = FilterStatus.FAILED_KEYWORD_FILTER,
            reason = reason
        )
        
        fun marketEndDateFailed(reason: String) = FilterResult(
            status = FilterStatus.FAILED_MARKET_END_DATE,
            reason = reason
        )
    }
}

