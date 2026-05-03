package com.wrbug.polymarketbot.service.market

import com.wrbug.polymarketbot.dto.MarketBettingEventDetail
import com.wrbug.polymarketbot.dto.MarketBettingEventSummary
import com.wrbug.polymarketbot.dto.MarketBettingHolder
import com.wrbug.polymarketbot.dto.MarketBettingMarketDetail
import com.wrbug.polymarketbot.dto.MarketBettingOutcomeDetail
import com.wrbug.polymarketbot.service.market.MarketBettingMarketText.displayOutcomeName
import com.wrbug.polymarketbot.service.market.MarketBettingMarketText.displayTitle
import com.wrbug.polymarketbot.service.market.MarketBettingMarketText.displayType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MarketBettingQueryFormatterTest {

    @Test
    fun `formats sports and grouped markets with Chinese labels`() {
        val detail = MarketBettingEventDetail(
            event = MarketBettingEventSummary(
                id = "389178",
                slug = "nhl-playoffs-who-will-win-series-wild-vs-stars",
                title = "NHL Playoffs: Who Will Win Series? - Wild vs. Stars",
                volume = "39872.18",
                liquidity = "25929.23",
                openInterest = "33660.22",
                active = true,
                closed = false,
                marketsCount = 2,
                url = "https://polymarket.com/event/nhl-playoffs-who-will-win-series-wild-vs-stars"
            ),
            markets = listOf(
                MarketBettingMarketDetail(
                    id = "2007253",
                    conditionId = "0x1a6f9fce14d84886cc433691b36e4efc0f1f13be6fa8b595c8189006b6fb8d55a",
                    slug = "nhl-playoffs-who-will-win-series-wild-vs-stars",
                    question = "NHL Playoffs: Who Will Win Series? - Wild vs. Stars",
                    marketType = "moneyline",
                    line = null,
                    groupItemTitle = null,
                    volume = "39872.18",
                    liquidity = "25929.23",
                    outcomes = listOf(
                        MarketBettingOutcomeDetail(
                            name = "Wild",
                            tokenId = "111",
                            odds = "0.45",
                            tradedShares = "530.5",
                            tradedAmount = "210.25",
                            bidOrderAmount = "1200.00",
                            askOrderAmount = "900.00",
                            topHolders = listOf(
                                MarketBettingHolder("0xaaa", "alpha", "320.5"),
                                MarketBettingHolder("0xbbb", null, "210")
                            )
                        ),
                        MarketBettingOutcomeDetail(
                            name = "Stars",
                            tokenId = "222",
                            odds = "0.55",
                            tradedShares = "231.3842",
                            tradedAmount = "128.40",
                            bidOrderAmount = "1500.00",
                            askOrderAmount = "1100.00",
                            topHolders = listOf(MarketBettingHolder("0xccc", "charlie", "231.3842"))
                        )
                    )
                ),
                MarketBettingMarketDetail(
                    id = "2007254",
                    conditionId = "0x2a6f9fce14d84886cc433691b36e4efc0f1f13be6fa8b595c8189006b6fb8d55a",
                    slug = "wild-stars-total-5pt5",
                    question = "Total goals over 5.5?",
                    marketType = "totals",
                    line = "5.5",
                    groupItemTitle = "Total Goals",
                    volume = "6000.00",
                    liquidity = "1900.00",
                    outcomes = emptyList()
                ),
                MarketBettingMarketDetail(
                    id = "2007255",
                    conditionId = "0x3a6f9fce14d84886cc433691b36e4efc0f1f13be6fa8b595c8189006b6fb8d55a",
                    slug = "wild-stars-spread-minus-1pt5",
                    question = "Spread -1.5",
                    marketType = "spreads",
                    line = "-1.5",
                    groupItemTitle = "Spread -1.5",
                    volume = "1200.00",
                    liquidity = "800.00",
                    outcomes = emptyList()
                )
            )
        )

        val formatted = MarketBettingQueryFormatter.formatEventDetail(detail)

        assertTrue(formatted.contains("总成交额: 39,872.18 USDC"))
        assertTrue(formatted.contains("类型: 胜负盘"))
        assertTrue(formatted.contains("类型: 大小盘 5.5"))
        assertTrue(formatted.contains("让分 -1.5"))
        assertTrue(formatted.contains("类型: 让分盘 -1.5"))
        assertTrue(formatted.contains("总进球"))
        assertTrue(formatted.contains("野队 45%"))
        assertTrue(formatted.contains("已成交份: 530.5"))
        assertTrue(formatted.contains("挂单: 买 1,200 USDC / 卖 900 USDC"))
        assertFalse(formatted.contains("moneyline"))
        assertFalse(formatted.contains("totals"))
        assertFalse(formatted.contains("Spread"))
        assertFalse(formatted.contains("shares"))
        assertFalse(formatted.contains("Top 5 shares"))
        assertFalse(formatted.contains("https://polymarket.com/profile/0xaaa"))
    }

    @Test
    fun `formats over under markets with Chinese outcome names`() {
        val title = displayTitle("Luquentz Dort: Points O/U 2.5")
        val type = displayType("points", "2.5")

        assertEquals("Luquentz Dort: 得分 大小 2.5", title)
        assertEquals("得分 2.5", type)
        assertEquals("大", displayOutcomeName("Yes", "Luquentz Dort: Points O/U 2.5", "points"))
        assertEquals("小", displayOutcomeName("No", "Luquentz Dort: Points O/U 2.5", "points"))
    }

    @Test
    fun `parses telegram market query commands`() {
        assertEquals("Wild vs Stars", MarketBettingTelegramCommandParser.parse("/盘口 Wild vs Stars")?.query)
        assertEquals("Trump", MarketBettingTelegramCommandParser.parse("盘口 Trump")?.query)
        assertEquals("World Cup", MarketBettingTelegramCommandParser.parse("/market World Cup")?.query)
        assertEquals("Celtics vs 76ers", MarketBettingTelegramCommandParser.parse("Celtics vs 76ers")?.query)
        assertEquals(null, MarketBettingTelegramCommandParser.parse("/start"))
    }
}
