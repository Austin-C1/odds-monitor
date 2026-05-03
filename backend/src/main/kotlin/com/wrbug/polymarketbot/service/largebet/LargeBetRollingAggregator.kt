package com.wrbug.polymarketbot.service.largebet

import java.math.BigDecimal
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap

data class LargeBetTradeEvent(
    val tradeId: String,
    val traderAddress: String,
    val traderName: String?,
    val marketId: String,
    val marketSlug: String?,
    val marketTitle: String,
    val sportType: String,
    val outcome: String,
    val price: BigDecimal,
    val size: BigDecimal,
    val timestampMillis: Long
) {
    val amount: BigDecimal get() = price.multiply(size)
}

data class LargeBetTriggerResult(
    val singleTriggered: Boolean,
    val cumulativeTriggered: Boolean,
    val singleAmount: BigDecimal,
    val cumulativeAmount: BigDecimal
) {
    val triggered: Boolean get() = singleTriggered || cumulativeTriggered
}

class LargeBetRollingAggregator {

    private data class BucketKey(
        val traderAddress: String,
        val marketId: String,
        val outcome: String
    )

    private val buckets = ConcurrentHashMap<BucketKey, ArrayDeque<LargeBetTradeEvent>>()

    fun record(
        event: LargeBetTradeEvent,
        singleTradeThreshold: BigDecimal,
        cumulativeTradeThreshold: BigDecimal,
        rollingWindowMinutes: Int
    ): LargeBetTriggerResult {
        val key = BucketKey(
            traderAddress = event.traderAddress.lowercase(),
            marketId = event.marketId,
            outcome = event.outcome
        )
        val windowStart = event.timestampMillis - rollingWindowMinutes * 60_000L
        val bucket = buckets.computeIfAbsent(key) { ArrayDeque() }

        synchronized(bucket) {
            bucket.addLast(event)
            while (bucket.isNotEmpty() && bucket.first.timestampMillis < windowStart) {
                bucket.removeFirst()
            }
            val cumulativeAmount = bucket.fold(BigDecimal.ZERO) { total, item -> total + item.amount }
            return LargeBetTriggerResult(
                singleTriggered = event.amount >= singleTradeThreshold,
                cumulativeTriggered = cumulativeAmount >= cumulativeTradeThreshold,
                singleAmount = event.amount,
                cumulativeAmount = cumulativeAmount
            )
        }
    }

    fun trackedBucketCount(): Int = buckets.size

    fun clear() {
        buckets.clear()
    }
}
