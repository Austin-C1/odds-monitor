package com.wrbug.polymarketbot.service.largebet

import com.wrbug.polymarketbot.entity.LargeBetWatchRecord
import com.wrbug.polymarketbot.repository.LargeBetWatchRecordRepository
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.`when`
import org.mockito.Mockito.verify
import org.mockito.Mockito.mock
import java.math.BigDecimal

class LargeBetWatchRecordServiceTest {

    private val repository = mock(LargeBetWatchRecordRepository::class.java)
    private val service = LargeBetWatchRecordService(repository)

    @Test
    fun `creates new watch record for first trigger`() = runBlocking {
        val event = event(timestampMillis = 1000L)
        `when`(repository.findByTraderAddressAndMarketIdAndOutcome(event.traderAddress.lowercase(), event.marketId, event.outcome))
            .thenReturn(null)
        `when`(repository.save(any())).thenAnswer { it.arguments[0] as LargeBetWatchRecord }

        val record = service.upsert(
            event = event,
            triggerReason = "SINGLE",
            singleAmount = BigDecimal("6000"),
            cumulativeAmount = BigDecimal("6000")
        )

        assertEquals(event.traderAddress.lowercase(), record.traderAddress)
        assertEquals("https://polymarket.com/profile/${event.traderAddress.lowercase()}", record.profileUrl)
        assertEquals(1, record.triggerCount)
        assertEquals("6000.00000000", record.lastSingleAmount)
    }

    @Test
    fun `updates existing watch record and increments trigger count`() = runBlocking {
        val event = event(timestampMillis = 2000L)
        val existing = LargeBetWatchRecord(
            id = 7L,
            traderAddress = event.traderAddress.lowercase(),
            traderName = "Old",
            profileUrl = "https://polymarket.com/profile/${event.traderAddress.lowercase()}",
            marketId = event.marketId,
            marketSlug = event.marketSlug,
            marketTitle = event.marketTitle,
            sportType = event.sportType,
            outcome = event.outcome,
            triggerReason = "SINGLE",
            lastSingleAmount = BigDecimal("6000"),
            lastCumulativeAmount = BigDecimal("6000"),
            firstTriggeredAt = 1000L,
            lastTriggeredAt = 1000L,
            triggerCount = 1,
            createdAt = 1000L,
            updatedAt = 1000L
        )
        `when`(repository.findByTraderAddressAndMarketIdAndOutcome(event.traderAddress.lowercase(), event.marketId, event.outcome))
            .thenReturn(existing)
        `when`(repository.save(any())).thenAnswer { it.arguments[0] as LargeBetWatchRecord }

        val record = service.upsert(
            event = event,
            triggerReason = "CUMULATIVE",
            singleAmount = BigDecimal("4000"),
            cumulativeAmount = BigDecimal("16000")
        )

        assertEquals(7L, record.id)
        assertEquals("Alpha", record.traderName)
        assertEquals("CUMULATIVE", record.triggerReason)
        assertEquals(2, record.triggerCount)
        assertEquals(1000L, record.firstTriggeredAt)
        assertEquals(2000L, record.lastTriggeredAt)
    }

    @Test
    fun `clear deletes all watch records`() = runBlocking {
        service.clearRecords()

        verify(repository).deleteAllInBatch()
    }

    private fun event(timestampMillis: Long) = LargeBetTradeEvent(
        tradeId = "trade-$timestampMillis",
        traderAddress = "0x1234567890123456789012345678901234567890",
        traderName = "Alpha",
        marketId = "0xmarket",
        marketSlug = "sample-market",
        marketTitle = "Sample market",
        sportType = "FOOTBALL",
        outcome = "YES",
        price = BigDecimal("0.5"),
        size = BigDecimal("12000"),
        timestampMillis = timestampMillis
    )
}
