package com.wrbug.polymarketbot.service.copytrading.statistics

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class TradeProcessingMutexRegistryTest {

    @Test
    fun `reuses the same mutex until every lease is released`() {
        val registry = TradeProcessingMutexRegistry()

        val first = registry.acquire("1_trade-1")
        val second = registry.acquire("1_trade-1")

        assertSame(first.mutex, second.mutex)
        assertEquals(1, registry.activeKeyCount())

        first.release()
        assertEquals(1, registry.activeKeyCount())

        second.release()
        assertEquals(0, registry.activeKeyCount())
    }

    @Test
    fun `keeps different trade keys isolated`() {
        val registry = TradeProcessingMutexRegistry()

        val first = registry.acquire("1_trade-1")
        val second = registry.acquire("2_trade-1")

        assertNotSame(first.mutex, second.mutex)
        assertEquals(2, registry.activeKeyCount())

        first.release()
        second.release()

        assertEquals(0, registry.activeKeyCount())
    }
}
