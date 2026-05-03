package com.wrbug.polymarketbot.service.largebet

import com.wrbug.polymarketbot.dto.ActivityTradePayload
import com.wrbug.polymarketbot.dto.ActivityTrader
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import java.math.BigDecimal

class LargeBetActivityMonitorServiceTest {

    private val service = LargeBetActivityMonitorService(
        configService = mock(),
        aggregator = LargeBetRollingAggregator(),
        watchRecordService = mock(),
        telegramAlertService = mock()
    )

    @Test
    fun `normalizes filled football activity trade`() {
        val event = service.normalize(
            ActivityTradePayload(
                asset = "token-1",
                conditionId = "0xmarket",
                eventSlug = "arsenal-vs-chelsea",
                outcome = "Arsenal",
                side = "BUY",
                price = "0.50",
                size = "12000",
                timestamp = 1_700_000_000L,
                transactionHash = "0xtx",
                trader = ActivityTrader(
                    name = "Alpha",
                    address = "0x1234567890123456789012345678901234567890"
                ),
                name = "Arsenal vs Chelsea football match"
            )
        )

        assertNotNull(event)
        assertEquals("0xtx", event!!.tradeId)
        assertEquals("FOOTBALL", event.sportType)
        assertEquals(BigDecimal("0.50"), event.price)
        assertEquals(BigDecimal("12000"), event.size)
        assertEquals(1_700_000_000_000L, event.timestampMillis)
    }

    @Test
    fun `ignores activity trade that is not football or basketball`() {
        val event = service.normalize(
            ActivityTradePayload(
                asset = "token-1",
                conditionId = "0xmarket",
                outcome = "YES",
                side = "BUY",
                price = "0.50",
                size = "12000",
                timestamp = 1_700_000_000L,
                proxyWallet = "0x1234567890123456789012345678901234567890",
                name = "Will it rain tomorrow?"
            )
        )

        assertNull(event)
    }
}
