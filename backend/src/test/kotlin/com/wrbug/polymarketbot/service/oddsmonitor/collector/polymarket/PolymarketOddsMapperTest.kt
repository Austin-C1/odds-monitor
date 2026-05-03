package com.wrbug.polymarketbot.service.oddsmonitor.collector.polymarket

import com.wrbug.polymarketbot.dto.MarketBettingEventDetail
import com.wrbug.polymarketbot.dto.MarketBettingEventSummary
import com.wrbug.polymarketbot.dto.MarketBettingMarketDetail
import com.wrbug.polymarketbot.dto.MarketBettingOutcomeDetail
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Instant

class PolymarketOddsMapperTest {
    @Test
    fun `maps football event probabilities into odds monitor rows`() {
        val detail = MarketBettingEventDetail(
            event = MarketBettingEventSummary(
                id = "event-1",
                slug = "kawasaki-frontale-vs-fc-tokyo-2026-05-03",
                title = "Kawasaki Frontale vs FC Tokyo",
                volume = "1000",
                liquidity = "500",
                openInterest = "0",
                active = true,
                closed = false,
                marketsCount = 2,
                url = "https://polymarket.com/event/kawasaki-frontale-vs-fc-tokyo-2026-05-03",
                category = "soccer",
                startDate = "2026-05-03T15:00:00Z"
            ),
            markets = listOf(
                market(
                    id = "m1",
                    type = "moneyline",
                    line = null,
                    outcomes = listOf(
                        outcome("Kawasaki Frontale", "0.56"),
                        outcome("Draw", "0.24"),
                        outcome("FC Tokyo", "0.20")
                    )
                ),
                market(
                    id = "m2",
                    type = "totals",
                    line = "2.5",
                    outcomes = listOf(outcome("Over", "0.48"), outcome("Under", "0.52"))
                )
            )
        )

        val mapped = PolymarketOddsMapper().map(detail, capturedAt = 1000L)

        assertEquals("kawasaki-frontale-vs-fc-tokyo-2026-05-03", mapped?.match?.sourceMatchId)
        assertEquals("Kawasaki Frontale", mapped?.match?.homeTeam)
        assertEquals("FC Tokyo", mapped?.match?.awayTeam)
        assertEquals(5, mapped?.rows?.size)
        assertEquals(
            listOf("moneyline:home:null:0.56", "moneyline:draw:null:0.24", "moneyline:away:null:0.20", "total:over:2.5:0.48", "total:under:2.5:0.52"),
            mapped?.rows?.map { "${it.marketType}:${it.selectionName}:${it.lineValue}:${it.oddsValue.toPlainString()}" }
        )
    }

    @Test
    fun `maps yes no moneyline markets by market question`() {
        val detail = MarketBettingEventDetail(
            event = MarketBettingEventSummary(
                id = "event-3",
                slug = "epl-mun-liv-2026-05-03",
                title = "Manchester United FC vs. Liverpool FC",
                volume = "1000",
                liquidity = "500",
                openInterest = "0",
                active = true,
                closed = false,
                marketsCount = 3,
                url = "https://polymarket.com/event/epl-mun-liv-2026-05-03",
                category = null,
                startDate = "2026-05-03T15:00:00Z"
            ),
            markets = listOf(
                market(
                    id = "m1",
                    type = "moneyline",
                    line = null,
                    question = "Will Manchester United FC win on 2026-05-03?",
                    outcomes = listOf(outcome("Yes", "0.425"), outcome("No", "0.575"))
                ),
                market(
                    id = "m2",
                    type = "moneyline",
                    line = null,
                    question = "Will Manchester United FC vs. Liverpool FC end in a draw?",
                    outcomes = listOf(outcome("Yes", "0.245"), outcome("No", "0.755"))
                ),
                market(
                    id = "m3",
                    type = "moneyline",
                    line = null,
                    question = "Will Liverpool FC win on 2026-05-03?",
                    outcomes = listOf(outcome("Yes", "0.325"), outcome("No", "0.675"))
                )
            )
        )

        val mapped = PolymarketOddsMapper().map(detail, capturedAt = 1000L)

        assertEquals(
            listOf("moneyline:home:null:0.425", "moneyline:draw:null:0.245", "moneyline:away:null:0.325"),
            mapped?.rows?.map { "${it.marketType}:${it.selectionName}:${it.lineValue}:${it.oddsValue.toPlainString()}" }
        )
    }

    @Test
    fun `uses event date from slug when polymarket start date is stale`() {
        val detail = MarketBettingEventDetail(
            event = MarketBettingEventSummary(
                id = "event-4",
                slug = "epl-mun-liv-2026-05-03",
                title = "Manchester United FC vs. Liverpool FC",
                volume = "1000",
                liquidity = "500",
                openInterest = "0",
                active = true,
                closed = false,
                marketsCount = 1,
                url = "https://polymarket.com/event/epl-mun-liv-2026-05-03",
                category = null,
                startDate = "2026-04-20T12:12:14Z"
            ),
            markets = listOf(
                market(
                    id = "m1",
                    type = "moneyline",
                    line = null,
                    question = "Will Manchester United FC win on 2026-05-03?",
                    outcomes = listOf(outcome("Yes", "0.425"), outcome("No", "0.575"))
                )
            )
        )

        val mapped = PolymarketOddsMapper().map(detail, capturedAt = 1000L)

        assertEquals(Instant.parse("2026-05-03T00:00:00Z").toEpochMilli(), mapped?.match?.startTime)
    }

    @Test
    fun `ignores basketball events even when they have moneyline markets`() {
        val detail = MarketBettingEventDetail(
            event = MarketBettingEventSummary(
                id = "event-2",
                slug = "lakers-vs-thunder-2026-05-03",
                title = "Lakers vs Thunder",
                volume = "1000",
                liquidity = "500",
                openInterest = "0",
                active = true,
                closed = false,
                marketsCount = 1,
                url = "https://polymarket.com/event/lakers-vs-thunder-2026-05-03",
                category = "basketball",
                startDate = "2026-05-03T15:00:00Z"
            ),
            markets = listOf(
                market(
                    id = "m1",
                    type = "moneyline",
                    line = null,
                    outcomes = listOf(outcome("Lakers", "0.56"), outcome("Thunder", "0.44"))
                )
            )
        )

        assertNull(PolymarketOddsMapper().map(detail, capturedAt = 1000L))
    }

    private fun market(
        id: String,
        type: String,
        line: String?,
        outcomes: List<MarketBettingOutcomeDetail>,
        question: String = "$type market"
    ) = MarketBettingMarketDetail(
        id = id,
        conditionId = id,
        slug = id,
        question = question,
        marketType = type,
        line = line,
        groupItemTitle = null,
        volume = "0",
        liquidity = "0",
        outcomes = outcomes
    )

    private fun outcome(name: String, odds: String) = MarketBettingOutcomeDetail(
        name = name,
        tokenId = name,
        odds = odds,
        bidOrderAmount = "0",
        askOrderAmount = "0",
        topHolders = emptyList()
    )
}
