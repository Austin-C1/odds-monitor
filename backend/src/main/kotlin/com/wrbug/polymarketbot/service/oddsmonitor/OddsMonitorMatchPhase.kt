package com.wrbug.polymarketbot.service.oddsmonitor

import com.wrbug.polymarketbot.dto.NotificationConfigData
import com.wrbug.polymarketbot.dto.NotificationConfigDto
import com.wrbug.polymarketbot.entity.OddsMatch
import com.wrbug.polymarketbot.entity.OddsPlatformMatch

enum class OddsMonitorMatchPhase {
    LIVE,
    PREMATCH
}

private val liveStatuses = setOf("live", "inplay", "in-play", "running", "started")
private val liveFlagPattern = Regex(""""is(?:_|-)?live"\s*:\s*true""", RegexOption.IGNORE_CASE)
private val liveStatusPattern = Regex(""""status"\s*:\s*"(live|inplay|in-play|running|started)"""", RegexOption.IGNORE_CASE)
private val scorePattern = Regex(""""score"\s*:\s*"([^"]*)"""", RegexOption.IGNORE_CASE)

fun determineOddsMonitorMatchPhase(
    platformMatch: OddsPlatformMatch,
    standardMatch: OddsMatch? = null,
    now: Long = System.currentTimeMillis()
): OddsMonitorMatchPhase {
    val status = standardMatch?.status?.trim()?.lowercase().orEmpty()
    if (status in liveStatuses) {
        return OddsMonitorMatchPhase.LIVE
    }
    if (rawPayloadIndicatesLive(platformMatch.rawPayloadJson)) {
        return OddsMonitorMatchPhase.LIVE
    }
    val startTime = standardMatch?.startTime ?: platformMatch.rawStartTime
    return if (startTime != null && startTime <= now) {
        OddsMonitorMatchPhase.LIVE
    } else {
        OddsMonitorMatchPhase.PREMATCH
    }
}

fun oddsMonitorStatusForPlatformMatch(
    platformMatch: OddsPlatformMatch,
    now: Long = System.currentTimeMillis()
): String {
    return when (determineOddsMonitorMatchPhase(platformMatch, null, now)) {
        OddsMonitorMatchPhase.LIVE -> "live"
        OddsMonitorMatchPhase.PREMATCH -> "scheduled"
    }
}

fun telegramConfigMatchesOddsMonitorPhase(
    config: NotificationConfigDto,
    phase: OddsMonitorMatchPhase
): Boolean {
    val telegram = config.config as? NotificationConfigData.Telegram ?: return false
    return when (phase) {
        OddsMonitorMatchPhase.LIVE -> telegram.data.liveOnlyModeEnabled
        OddsMonitorMatchPhase.PREMATCH -> !telegram.data.liveOnlyModeEnabled
    }
}

private fun rawPayloadIndicatesLive(rawPayloadJson: String?): Boolean {
    val payload = rawPayloadJson?.takeIf { it.isNotBlank() } ?: return false
    if (liveFlagPattern.containsMatchIn(payload) || liveStatusPattern.containsMatchIn(payload)) {
        return true
    }
    val score = scorePattern.find(payload)?.groupValues?.getOrNull(1)
    return !score.isNullOrBlank()
}
