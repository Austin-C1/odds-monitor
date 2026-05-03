package com.wrbug.polymarketbot.service.system

import com.wrbug.polymarketbot.dto.MarketBettingEventDetail
import com.wrbug.polymarketbot.dto.NotificationConfigData
import com.wrbug.polymarketbot.dto.NotificationConfigDto
import com.wrbug.polymarketbot.service.market.MarketBettingMarketText
import com.wrbug.polymarketbot.service.market.MarketBettingQueryService
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.math.RoundingMode
import java.text.DecimalFormat
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

private val beijingZone: ZoneId = ZoneId.of("Asia/Shanghai")
private val dailyReportTimeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

internal fun filterMarketBettingDailyReportTelegramConfigs(configs: List<NotificationConfigDto>): List<NotificationConfigDto> {
    return configs.filter { config ->
        val telegram = config.config as? NotificationConfigData.Telegram ?: return@filter false
        telegram.data.marketBettingQueryEnabled &&
            telegram.data.marketBettingDailyReportEnabled &&
            telegram.data.botToken.isNotBlank() &&
            telegram.data.chatIds.isNotEmpty()
    }
}

class MarketBettingDailyReportRunState {
    private val lastRuns = mutableMapOf<Long, String>()

    fun shouldRun(configId: Long, scheduledTime: String, now: ZonedDateTime = ZonedDateTime.now(beijingZone)): Boolean {
        val normalizedTime = normalizeDailyReportTime(scheduledTime) ?: return false
        if (now.format(dailyReportTimeFormatter) != normalizedTime) return false

        val runKey = "${now.toLocalDate()} $normalizedTime"
        if (lastRuns[configId] == runKey) return false
        lastRuns[configId] = runKey
        return true
    }

    private fun normalizeDailyReportTime(value: String): String? {
        val trimmed = value.trim()
        return if (Regex("""^([01]\d|2[0-3]):[0-5]\d$""").matches(trimmed)) trimmed else null
    }
}

object MarketBettingDailyReportFormatter {
    fun format(details: List<MarketBettingEventDetail>): String {
        val activeDetails = details.filter { it.markets.isNotEmpty() && !it.event.closed }
        if (activeDetails.isEmpty()) {
            return "每日盘口投注额汇总\n暂无未结束的足球或篮球比赛盘口。"
        }

        return buildString {
            appendLine("每日盘口投注额汇总")
            appendLine("范围: 未结束的足球和篮球比赛")
            activeDetails.forEachIndexed { index, detail ->
                appendLine()
                appendLine("${index + 1}. ${MarketBettingMarketText.displayEventTitle(detail.event.title)}")
                appendLine("类型: ${inferSport(detail)}")
                appendLine("总成交额: ${formatUsdc(detail.event.volume)}")
                appendLine("挂单金额: ${formatUsdc(detail.event.liquidity)}")
                appendLine("盘口数: ${detail.markets.size}")
                appendLine(detail.event.url)
            }
        }.trim()
    }

    private fun inferSport(detail: MarketBettingEventDetail): String {
        val text = listOf(detail.event.title, detail.event.slug, detail.event.category.orEmpty()).joinToString(" ").lowercase()
        return if (listOf("nba", "basketball", "wnba", "cbb").any { it in text }) "篮球" else "足球"
    }

    private fun formatUsdc(value: String): String {
        val amount = value.toBigDecimalOrNull() ?: return "$value USDC"
        return "${DecimalFormat("#,##0.####").format(amount.setScale(4, RoundingMode.HALF_UP).stripTrailingZeros())} USDC"
    }
}

@Service
class MarketBettingDailyReportScheduler(
    private val notificationConfigService: NotificationConfigService,
    private val telegramNotificationService: TelegramNotificationService,
    private val marketBettingQueryService: MarketBettingQueryService
) {
    private val logger = LoggerFactory.getLogger(MarketBettingDailyReportScheduler::class.java)
    private val runState = MarketBettingDailyReportRunState()

    @Scheduled(fixedDelay = 60_000)
    fun sendDailyReports() {
        runBlocking {
            val now = ZonedDateTime.now(beijingZone)
            val dueConfigs = filterMarketBettingDailyReportTelegramConfigs(
                notificationConfigService.getEnabledConfigsByType("telegram")
            ).filter { config ->
                val telegram = config.config as? NotificationConfigData.Telegram ?: return@filter false
                runState.shouldRun(config.id ?: return@filter false, telegram.data.marketBettingDailyReportTime, now)
            }

            if (dueConfigs.isEmpty()) return@runBlocking

            val message = marketBettingQueryService.activeFootballAndBasketballDetails()
                .map { MarketBettingDailyReportFormatter.format(it) }
                .getOrElse { error ->
                    logger.warn("每日盘口投注额汇总失败: {}", error.message)
                    "每日盘口投注额汇总\n查询失败: ${error.message ?: "未知错误"}"
                }

            dueConfigs.forEach { config ->
                val telegram = config.config as? NotificationConfigData.Telegram ?: return@forEach
                telegram.data.chatIds.forEach { chatId ->
                    sendChunks(telegram.data.botToken, chatId, message)
                }
            }
        }
    }

    private suspend fun sendChunks(botToken: String, chatId: String, message: String) {
        message.chunked(3500).forEach { chunk ->
            telegramNotificationService.sendMessage(botToken, chatId, chunk)
        }
    }
}
