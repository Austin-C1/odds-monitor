package com.wrbug.polymarketbot.service.copytrading.statistics

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class LeaderProfitTakeEvaluatorTest {

    private val evaluator = LeaderProfitTakeEvaluator()

    @Test
    fun `triggers a full sell when price reaches the configured cap`() {
        val result = evaluator.evaluate(
            enabled = true,
            triggerPrice = BigDecimal("0.99"),
            currentPrice = BigDecimal("0.9912"),
            remainingQuantity = BigDecimal("42")
        )

        assertTrue(result.shouldSell)
        assertEquals(BigDecimal("42"), result.sellQuantity)
    }

    @Test
    fun `does not sell before the configured cap is reached`() {
        val result = evaluator.evaluate(
            enabled = true,
            triggerPrice = BigDecimal("0.99"),
            currentPrice = BigDecimal("0.98"),
            remainingQuantity = BigDecimal("42")
        )

        assertFalse(result.shouldSell)
        assertEquals(BigDecimal.ZERO, result.sellQuantity)
    }

    @Test
    fun `does not sell when the rule is disabled or there is no remaining quantity`() {
        val disabled = evaluator.evaluate(
            enabled = false,
            triggerPrice = BigDecimal("0.99"),
            currentPrice = BigDecimal("0.995"),
            remainingQuantity = BigDecimal("42")
        )
        val empty = evaluator.evaluate(
            enabled = true,
            triggerPrice = BigDecimal("0.99"),
            currentPrice = BigDecimal("0.995"),
            remainingQuantity = BigDecimal.ZERO
        )

        assertFalse(disabled.shouldSell)
        assertEquals(BigDecimal.ZERO, disabled.sellQuantity)
        assertFalse(empty.shouldSell)
        assertEquals(BigDecimal.ZERO, empty.sellQuantity)
    }
}
