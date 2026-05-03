package com.wrbug.polymarketbot.service.copytrading.statistics

import java.math.BigDecimal
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LeaderMonitorAlertServiceTest {

    @Test
    fun `same side alerts should include merged holding and average price report`() {
        val alerts = buildSameSideMonitorAlerts(
            listOf(
                position(leaderId = 1L, leaderName = "Austin", outcomeIndex = 0, outcome = "YES", currentValue = "320", avgPrice = "0.61"),
                position(leaderId = 2L, leaderName = "debased", outcomeIndex = 0, outcome = "YES", currentValue = "185", avgPrice = "0.58"),
                position(leaderId = 3L, leaderName = "crocodile", outcomeIndex = 1, outcome = "NO", currentValue = "92", avgPrice = "0.63")
            )
        )

        assertEquals(1, alerts.size)
        assertEquals("YES", alerts.single().outcome)
        assertEquals(2, alerts.single().sameSideCount)
        assertEquals(listOf("sports"), alerts.single().leaderGroups)
        assertTrue(alerts.single().sameSidePositionReport.contains("Austin｜持仓报告: <code>320u @ 0.61</code>"))
        assertTrue(alerts.single().sameSidePositionReport.contains("debased｜持仓报告: <code>185u @ 0.58</code>"))
    }

    @Test
    fun `opposite alerts should include hedge report when one leader holds both sides`() {
        val alert = buildOppositeMonitorAlert(
            listOf(
                position(leaderId = 1L, leaderName = "Austin", outcomeIndex = 0, outcome = "YES", currentValue = "320", avgPrice = "0.61"),
                position(leaderId = 2L, leaderName = "debased", outcomeIndex = 1, outcome = "NO", currentValue = "185", avgPrice = "0.42"),
                position(leaderId = 3L, leaderName = "hedger", outcomeIndex = 0, outcome = "YES", currentValue = "210", avgPrice = "0.59"),
                position(leaderId = 3L, leaderName = "hedger", outcomeIndex = 1, outcome = "NO", currentValue = "95", avgPrice = "0.41")
            )
        )

        requireNotNull(alert)
        assertEquals("YES", alert.outcomeA)
        assertEquals("NO", alert.outcomeB)
        assertEquals(listOf("sports"), alert.leaderGroups)
        assertTrue(alert.sideAPositionReport.contains("Austin｜持仓报告: <code>320u @ 0.61</code>"))
        assertTrue(alert.sideBPositionReport.contains("debased｜持仓报告: <code>185u @ 0.42</code>"))
        assertTrue(alert.hedgePositionReport.contains("hedger｜YES: <code>210u @ 0.59</code>｜NO: <code>95u @ 0.41</code>"))
    }

    private fun position(
        leaderId: Long,
        leaderName: String,
        outcomeIndex: Int,
        outcome: String,
        currentValue: String,
        avgPrice: String,
        leaderGroup: String? = "sports"
    ) = LeaderMonitorPosition(
        leaderId = leaderId,
        leaderName = leaderName,
        marketId = "condition-1",
        marketTitle = "Will Austin ship monitor mode?",
        marketLink = "https://polymarket.com/event/will-austin-ship-monitor-mode",
        outcomeIndex = outcomeIndex,
        outcome = outcome,
        currentValue = BigDecimal(currentValue),
        avgPrice = BigDecimal(avgPrice),
        leaderGroup = leaderGroup
    )
}
