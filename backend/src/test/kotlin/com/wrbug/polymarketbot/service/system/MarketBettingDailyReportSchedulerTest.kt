package com.wrbug.polymarketbot.service.system

import com.wrbug.polymarketbot.dto.MarketBettingEventDetail
import com.wrbug.polymarketbot.dto.MarketBettingEventSummary
import com.wrbug.polymarketbot.dto.MarketBettingMarketDetail
import com.wrbug.polymarketbot.dto.NotificationConfigData
import com.wrbug.polymarketbot.dto.NotificationConfigDto
import com.wrbug.polymarketbot.dto.TelegramConfigData
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

class MarketBettingDailyReportSchedulerTest {

    @Test
    fun `daily report should run at selected Beijing time once per day`() {
        val state = MarketBettingDailyReportRunState()
        val now = ZonedDateTime.of(
            LocalDateTime.of(LocalDate.of(2026, 4, 30), LocalTime.of(2, 0)),
            ZoneId.of("Asia/Shanghai")
        )

        assertTrue(state.shouldRun(configId = 7L, scheduledTime = "02:00", now = now))
        assertFalse(state.shouldRun(configId = 7L, scheduledTime = "02:00", now = now.plusSeconds(30)))
        assertFalse(state.shouldRun(configId = 7L, scheduledTime = "02:01", now = now))
        assertTrue(state.shouldRun(configId = 7L, scheduledTime = "02:00", now = now.plusDays(1)))
    }

    @Test
    fun `daily report should format event totals and skip events without markets`() {
        val report = MarketBettingDailyReportFormatter.format(
            listOf(
                detail(
                    title = "Magic vs Pistons",
                    volume = "431600.0384",
                    liquidity = "2148199.2325",
                    marketsCount = 3
                ),
                detail(
                    title = "Empty Event",
                    volume = "10",
                    liquidity = "20",
                    marketsCount = 0
                )
            )
        )

        assertTrue(report.contains("每日盘口投注额汇总"))
        assertTrue(report.contains("\u9b54\u672f"))
        assertTrue(report.contains("\u6d3b\u585e"))
        assertFalse(report.contains("Magic vs Pistons"))
        assertTrue(report.contains("总成交额: 431,600.0384 USDC"))
        assertTrue(report.contains("挂单金额: 2,148,199.2325 USDC"))
        assertTrue(report.contains("盘口数: 3"))
        assertFalse(report.contains("Empty Event"))
    }

    @Test
    fun `daily report should use only selected query bots with daily report enabled`() {
        val configs = listOf(
            telegramConfig(id = 1L, queryEnabled = true, dailyEnabled = true),
            telegramConfig(id = 2L, queryEnabled = true, dailyEnabled = false),
            telegramConfig(id = 3L, queryEnabled = false, dailyEnabled = true)
        )

        val filtered = filterMarketBettingDailyReportTelegramConfigs(configs)

        assertTrue(filtered.map { it.id }.contains(1L))
        assertFalse(filtered.map { it.id }.contains(2L))
        assertFalse(filtered.map { it.id }.contains(3L))
    }

    private fun detail(
        title: String,
        volume: String,
        liquidity: String,
        marketsCount: Int
    ): MarketBettingEventDetail {
        return MarketBettingEventDetail(
            event = MarketBettingEventSummary(
                id = title,
                slug = title.lowercase().replace(" ", "-"),
                title = title,
                volume = volume,
                liquidity = liquidity,
                openInterest = "0",
                active = true,
                closed = false,
                marketsCount = marketsCount,
                url = "https://polymarket.com/event/${title.lowercase().replace(" ", "-")}"
            ),
            markets = List(marketsCount) { index ->
                MarketBettingMarketDetail(
                    id = "$index",
                    conditionId = "$index",
                    slug = "$index",
                    question = title,
                    marketType = "moneyline",
                    line = null,
                    groupItemTitle = null,
                    volume = "1",
                    liquidity = "1",
                    outcomes = emptyList()
                )
            }
        )
    }

    private fun telegramConfig(id: Long, queryEnabled: Boolean, dailyEnabled: Boolean): NotificationConfigDto {
        return NotificationConfigDto(
            id = id,
            type = "telegram",
            name = "bot-$id",
            enabled = true,
            config = NotificationConfigData.Telegram(
                TelegramConfigData(
                    botToken = "token-$id",
                    chatIds = listOf("chat-$id"),
                    marketBettingQueryEnabled = queryEnabled,
                    marketBettingDailyReportEnabled = dailyEnabled,
                    marketBettingDailyReportTime = "02:00"
                )
            )
        )
    }
}
