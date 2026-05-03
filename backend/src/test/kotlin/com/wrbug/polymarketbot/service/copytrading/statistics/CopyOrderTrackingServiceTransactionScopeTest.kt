package com.wrbug.polymarketbot.service.copytrading.statistics

import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.springframework.transaction.annotation.Transactional

class CopyOrderTrackingServiceTransactionScopeTest {

    @Test
    fun `trade processing entrypoints should not keep a transaction open across remote calls`() {
        val processTrade = CopyOrderTrackingService::class.java.methods.first { it.name.startsWith("processTrade") }
        val processBuyTrade = CopyOrderTrackingService::class.java.methods.first { it.name.startsWith("processBuyTrade") }
        val processSellTrade = CopyOrderTrackingService::class.java.methods.first { it.name.startsWith("processSellTrade") }

        assertNull(processTrade.getAnnotation(Transactional::class.java))
        assertNull(processBuyTrade.getAnnotation(Transactional::class.java))
        assertNull(processSellTrade.getAnnotation(Transactional::class.java))
    }
}
