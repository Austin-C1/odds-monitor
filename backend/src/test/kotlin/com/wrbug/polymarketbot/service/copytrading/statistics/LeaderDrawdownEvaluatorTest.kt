package com.wrbug.polymarketbot.service.copytrading.statistics

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class LeaderDrawdownEvaluatorTest {

    private val evaluator = LeaderDrawdownEvaluator()

    @Test
    fun `pauses when cumulative pnl falls 25 percent from the weekly positive peak`() {
        val result = evaluator.evaluate(
            points = listOf(
                LeaderCurvePoint(1L, BigDecimal("30")),
                LeaderCurvePoint(2L, BigDecimal("80")),
                LeaderCurvePoint(3L, BigDecimal("60"))
            ),
            thresholdPercent = BigDecimal("25")
        )

        assertTrue(result.shouldPause)
        assertEquals(BigDecimal("80"), result.peakPnl)
        assertEquals(BigDecimal("60"), result.currentPnl)
        assertEquals("25.00", result.drawdownPercent.toPlainString())
    }

    @Test
    fun `does not pause when there is no positive weekly peak yet`() {
        val result = evaluator.evaluate(
            points = listOf(
                LeaderCurvePoint(1L, BigDecimal("-20")),
                LeaderCurvePoint(2L, BigDecimal("-10")),
                LeaderCurvePoint(3L, BigDecimal("-30"))
            ),
            thresholdPercent = BigDecimal("25")
        )

        assertFalse(result.shouldPause)
        assertEquals(BigDecimal.ZERO, result.peakPnl)
        assertEquals(BigDecimal("-30"), result.currentPnl)
        assertEquals(BigDecimal.ZERO.setScale(2), result.drawdownPercent)
    }

    @Test
    fun `does not pause when drawdown stays below threshold`() {
        val result = evaluator.evaluate(
            points = listOf(
                LeaderCurvePoint(1L, BigDecimal("40")),
                LeaderCurvePoint(2L, BigDecimal("100")),
                LeaderCurvePoint(3L, BigDecimal("90"))
            ),
            thresholdPercent = BigDecimal("25")
        )

        assertFalse(result.shouldPause)
        assertEquals("10.00", result.drawdownPercent.toPlainString())
    }
}
