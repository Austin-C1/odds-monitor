package com.wrbug.polymarketbot.service.largebet

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import java.math.BigDecimal

class LargeBetTelegramAlertServiceTest {

    private val service = LargeBetTelegramAlertService(mock())

    @Test
    fun `message links polymarket user name to profile`() {
        val message = service.buildMessage(
            event = event(traderName = "Alpha <Fund>"),
            triggerReason = "SINGLE",
            singleAmount = BigDecimal("6000"),
            cumulativeAmount = BigDecimal("6000")
        )

        assertTrue(message.contains("<a href=\"https://polymarket.com/profile/0x1234567890123456789012345678901234567890\">Alpha &lt;Fund&gt;</a>"))
        assertTrue(message.contains("<a href=\"https://polymarket.com/event/sample-market\">Sample market</a>"))
        assertTrue(message.contains("<code>6000.0000</code> USDC"))
    }

    @Test
    fun `message falls back to shortened address when name is missing`() {
        val message = service.buildMessage(
            event = event(traderName = null),
            triggerReason = "CUMULATIVE",
            singleAmount = BigDecimal("4000"),
            cumulativeAmount = BigDecimal("16000")
        )

        assertTrue(message.contains("<a href=\"https://polymarket.com/profile/0x1234567890123456789012345678901234567890\">0x1234...7890</a>"))
        assertTrue(message.contains("窗口累计"))
    }

    @Test
    fun `message localizes large bet market fields`() {
        val message = service.buildMessage(
            event = event(
                traderName = "superbeter007",
                marketTitle = "Pistons vs. Magic Odds & Predictions (Apr. 29, 2026)",
                sportType = "BASKETBALL",
                outcome = "Under"
            ),
            triggerReason = "BOTH",
            singleAmount = BigDecimal("30855"),
            cumulativeAmount = BigDecimal("30855")
        )

        assertTrue(message.contains("盘口: <a href=\"https://polymarket.com/event/sample-market\">活塞 对 魔术 赔率与预测 (Apr. 29, 2026)</a>"))
        assertTrue(message.contains("类型: 篮球"))
        assertTrue(message.contains("方向: <b>小</b>"))
        assertTrue(message.contains("触发原因: 单笔和窗口累计"))
        assertTrue(message.contains("用户: <a href=\"https://polymarket.com/profile/0x1234567890123456789012345678901234567890\">superbeter007</a>"))
    }

    private fun event(
        traderName: String?,
        marketTitle: String = "Sample market",
        sportType: String = "FOOTBALL",
        outcome: String = "YES"
    ) = LargeBetTradeEvent(
        tradeId = "trade-1",
        traderAddress = "0x1234567890123456789012345678901234567890",
        traderName = traderName,
        marketId = "0xmarket",
        marketSlug = "sample-market",
        marketTitle = marketTitle,
        sportType = sportType,
        outcome = outcome,
        price = BigDecimal("0.5"),
        size = BigDecimal("12000"),
        timestampMillis = 1_700_000_000_000L
    )
}
