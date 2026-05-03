package com.wrbug.polymarketbot.service.largebet

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class LargeBetRollingAggregatorTest {

    private val baseTime = 1_700_000_000_000L

    @Test
    fun `single trade over threshold triggers single alert`() {
        val aggregator = LargeBetRollingAggregator()

        val result = aggregator.record(
            event = event(price = "0.5", size = "12000", timestampMillis = baseTime),
            singleTradeThreshold = BigDecimal("5000"),
            cumulativeTradeThreshold = BigDecimal("15000"),
            rollingWindowMinutes = 60
        )

        assertTrue(result.singleTriggered)
        assertFalse(result.cumulativeTriggered)
        assertEquals(BigDecimal("6000.0"), result.singleAmount)
        assertEquals(BigDecimal("6000.0"), result.cumulativeAmount)
    }

    @Test
    fun `same user market and outcome cumulative amount triggers cumulative alert`() {
        val aggregator = LargeBetRollingAggregator()

        repeat(2) { index ->
            aggregator.record(
                event = event(price = "0.5", size = "12000", timestampMillis = baseTime + index * 1000L),
                singleTradeThreshold = BigDecimal("10000"),
                cumulativeTradeThreshold = BigDecimal("15000"),
                rollingWindowMinutes = 60
            )
        }

        val result = aggregator.record(
            event = event(price = "0.5", size = "12000", timestampMillis = baseTime + 2000L),
            singleTradeThreshold = BigDecimal("10000"),
            cumulativeTradeThreshold = BigDecimal("15000"),
            rollingWindowMinutes = 60
        )

        assertFalse(result.singleTriggered)
        assertTrue(result.cumulativeTriggered)
        assertEquals(BigDecimal("18000.0"), result.cumulativeAmount)
    }

    @Test
    fun `old trades outside rolling window are not included`() {
        val aggregator = LargeBetRollingAggregator()

        aggregator.record(
            event = event(price = "0.5", size = "20000", timestampMillis = baseTime),
            singleTradeThreshold = BigDecimal("20000"),
            cumulativeTradeThreshold = BigDecimal("15000"),
            rollingWindowMinutes = 60
        )

        val result = aggregator.record(
            event = event(price = "0.5", size = "12000", timestampMillis = baseTime + 61 * 60 * 1000L),
            singleTradeThreshold = BigDecimal("20000"),
            cumulativeTradeThreshold = BigDecimal("15000"),
            rollingWindowMinutes = 60
        )

        assertFalse(result.singleTriggered)
        assertFalse(result.cumulativeTriggered)
        assertEquals(BigDecimal("6000.0"), result.cumulativeAmount)
    }

    @Test
    fun `same user and market different outcomes use separate buckets`() {
        val aggregator = LargeBetRollingAggregator()

        aggregator.record(
            event = event(outcome = "YES", price = "0.5", size = "20000", timestampMillis = baseTime),
            singleTradeThreshold = BigDecimal("20000"),
            cumulativeTradeThreshold = BigDecimal("15000"),
            rollingWindowMinutes = 60
        )

        val result = aggregator.record(
            event = event(outcome = "NO", price = "0.5", size = "12000", timestampMillis = baseTime + 1000L),
            singleTradeThreshold = BigDecimal("20000"),
            cumulativeTradeThreshold = BigDecimal("15000"),
            rollingWindowMinutes = 60
        )

        assertFalse(result.singleTriggered)
        assertFalse(result.cumulativeTriggered)
        assertEquals(BigDecimal("6000.0"), result.cumulativeAmount)
    }

    @Test
    fun `clear removes all tracked buckets`() {
        val aggregator = LargeBetRollingAggregator()

        aggregator.record(
            event = event(outcome = "YES", price = "0.5", size = "12000", timestampMillis = baseTime),
            singleTradeThreshold = BigDecimal("10000"),
            cumulativeTradeThreshold = BigDecimal("15000"),
            rollingWindowMinutes = 60
        )
        aggregator.record(
            event = event(outcome = "NO", price = "0.5", size = "12000", timestampMillis = baseTime),
            singleTradeThreshold = BigDecimal("10000"),
            cumulativeTradeThreshold = BigDecimal("15000"),
            rollingWindowMinutes = 60
        )

        assertEquals(2, aggregator.trackedBucketCount())

        aggregator.clear()

        assertEquals(0, aggregator.trackedBucketCount())
    }

    private fun event(
        outcome: String = "YES",
        price: String,
        size: String,
        timestampMillis: Long
    ) = LargeBetTradeEvent(
        tradeId = "trade-$outcome-$timestampMillis",
        traderAddress = "0x1234567890123456789012345678901234567890",
        traderName = "Alpha",
        marketId = "0xmarket",
        marketSlug = "sample-market",
        marketTitle = "Sample market",
        sportType = "FOOTBALL",
        outcome = outcome,
        price = BigDecimal(price),
        size = BigDecimal(size),
        timestampMillis = timestampMillis
    )
}
