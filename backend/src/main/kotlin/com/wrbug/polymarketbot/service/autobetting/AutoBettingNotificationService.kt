package com.wrbug.polymarketbot.service.autobetting

import com.wrbug.polymarketbot.entity.AutoBettingIntent
import com.wrbug.polymarketbot.service.system.NotificationTemplateService
import com.wrbug.polymarketbot.service.system.TelegramNotificationService
import com.wrbug.polymarketbot.util.TextEncodingUtils
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.math.RoundingMode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Service
class AutoBettingNotificationService(
    private val notificationTemplateService: NotificationTemplateService,
    private val telegramNotificationService: TelegramNotificationService
) {
    private val logger = LoggerFactory.getLogger(AutoBettingNotificationService::class.java)

    fun sendPlacedIntent(intent: AutoBettingIntent, now: Long = System.currentTimeMillis()) {
        val variables = buildBettingSuccessTemplateVariables(intent, now)
        val message = notificationTemplateService.renderTemplate(BETTING_TEMPLATE, variables)
            .ifBlank { buildFallbackMessage(variables) }

        runCatching {
            runBlocking {
                telegramNotificationService.sendBettingSuccessMessage(message)
            }
        }.onFailure { error ->
            logger.error("Failed to send auto betting success notification: {}", error.message, error)
        }
    }

    private fun buildFallbackMessage(variables: Map<String, String>): String {
        return """
            <b>投注成功</b>
            账号：${variables["account_name"] ?: "-"}
            联赛：${variables["league_name"] ?: "-"}
            比赛：${variables["match_title"] ?: "-"}
            盘口：${variables["market_title"] ?: "-"}
            选择：${variables["selection_name"] ?: "-"}
            赔率：<code>${variables["odds"] ?: "-"}</code>
            金额：<code>${variables["amount"] ?: "-"}</code>
            时间：<code>${variables["time"] ?: "-"}</code>
        """.trimIndent()
    }

    companion object {
        private const val BETTING_TEMPLATE = "BETTING_TEMPLATE"
    }
}

fun buildBettingSuccessTemplateVariables(intent: AutoBettingIntent, now: Long = System.currentTimeMillis()): Map<String, String> {
    val accountName = intent.accountDisplayName
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?: intent.accountKey

    return mapOf(
        "account_name" to htmlEscape(TextEncodingUtils.repairMojibake(accountName)),
        "account_key" to htmlEscape(intent.accountKey),
        "league_name" to htmlEscape(TextEncodingUtils.repairMojibake(intent.leagueName)),
        "match_title" to htmlEscape(TextEncodingUtils.repairMojibake(intent.matchTitle)),
        "market_title" to htmlEscape(formatMarketTitle(intent)),
        "selection_name" to htmlEscape(formatSelectionName(intent.selectionName)),
        "odds" to intent.targetOdds.setScale(3, RoundingMode.HALF_UP).toPlainString(),
        "amount" to intent.stakeAmount.setScale(2, RoundingMode.HALF_UP).toPlainString(),
        "time" to SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date(now))
    )
}

private fun formatMarketTitle(intent: AutoBettingIntent): String {
    val line = intent.lineValue?.trim()?.takeIf { it.isNotBlank() }
    val label = when (intent.marketType.trim().lowercase(Locale.ROOT)) {
        "handicap" -> "让球"
        "total" -> "大小球"
        else -> TextEncodingUtils.repairMojibake(intent.marketType)
    }
    return listOfNotNull(label, line).joinToString(" ")
}

private fun formatSelectionName(selectionName: String): String {
    val repaired = TextEncodingUtils.repairMojibake(selectionName)
    return when (repaired.trim().lowercase(Locale.ROOT)) {
        "home" -> "主队"
        "away" -> "客队"
        "over", "o" -> "大球"
        "under", "u" -> "小球"
        else -> repaired
    }
}

private fun htmlEscape(value: String): String {
    return value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
}
