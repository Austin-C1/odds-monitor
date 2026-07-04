package com.wrbug.polymarketbot.service.oddsmonitor

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class OddsMonitorDisplayMapperTest {
    private val mapper = OddsMonitorDisplayMapper()

    @Test
    fun `maps crown source and match display text`() {
        assertEquals("皇冠", mapper.sourceDisplayName("crown", "Crown"))
        assertEquals("日本J1百年构想联赛", mapper.leagueName("Japan J1 League"))
        assertEquals("东京FC", mapper.teamName("FC Tokyo"))
        assertEquals("滚球", mapper.matchStatus("live"))
    }

    @Test
    fun `repairs legacy alert templates into readable text`() {
        val title = "赔率变动：TextEncodingUtils.repairMojibake(pending.matchName)"
        val message = """
            联赛：日本J1
            比赛：川崎前锋 vs 东京
            盘口：escapeHtml(TextEncodingUtils.repairMojibake(market.marketLabel))
            皇冠：0.83 -> 0.85
        """.trimIndent()

        assertEquals("赔率变动：川崎前锋 vs 东京", mapper.alertTitle(title, message))
        assertEquals(
            """
            联赛：日本J1
            比赛：川崎前锋 vs 东京
            皇冠：0.83 -> 0.85
            """.trimIndent(),
            mapper.alertMessage(message)
        )
    }
}
