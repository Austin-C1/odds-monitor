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
private val homeScorePattern = Regex(""""(?:home_score|homeScore|homeGoals|score_h|scoreH|score_home|h_score)"\s*:\s*"?(\d+)"""", RegexOption.IGNORE_CASE)
private val awayScorePattern = Regex(""""(?:away_score|awayScore|awayGoals|score_c|scoreC|score_away|c_score)"\s*:\s*"?(\d+)"""", RegexOption.IGNORE_CASE)
private val elapsedNumberPattern = Regex(""""(?:elapsed_minutes|elapsedMinutes|match_minute|matchMinute|minute|minutes)"\s*:\s*"?(\d{1,3})"""", RegexOption.IGNORE_CASE)
private val elapsedTextPattern = Regex(""""(?:elapsed_time|elapsedTime|match_time|matchTime|retimeset|rb_time|rbTime|live_time|liveTime|game_time|gameTime|clock|timer)"\s*:\s*"([^"]+)"""", RegexOption.IGNORE_CASE)

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
    phase: OddsMonitorMatchPhase,
    startTime: Long? = null,
    now: Long = System.currentTimeMillis(),
    liveObservationMinutes: Int? = null
): Boolean {
    val telegram = config.config as? NotificationConfigData.Telegram ?: return false
    return when (phase) {
        OddsMonitorMatchPhase.LIVE -> {
            if (!telegram.data.liveOnlyModeEnabled) {
                return false
            }
            val limitMinutes = liveObservationMinutes?.takeIf { it > 0 } ?: return true
            val kickoffTime = startTime ?: return false
            val elapsedMillis = now - kickoffTime
            elapsedMillis in 0..(limitMinutes * 60_000L)
        }
        OddsMonitorMatchPhase.PREMATCH -> {
            if (telegram.data.liveOnlyModeEnabled) {
                return false
            }
            val windowMinutes = telegram.data.prematchWindowMinutes?.takeIf { it > 0 } ?: return true
            val kickoffTime = startTime ?: return false
            val remainingMillis = kickoffTime - now
            remainingMillis in 0..(windowMinutes * 60_000L)
        }
    }
}

private fun rawPayloadIndicatesLive(rawPayloadJson: String?): Boolean {
    val payload = rawPayloadJson?.takeIf { it.isNotBlank() } ?: return false
    if (liveFlagPattern.containsMatchIn(payload) || liveStatusPattern.containsMatchIn(payload)) {
        return true
    }
    return !oddsMonitorLiveScoreText(payload).isNullOrBlank() ||
        oddsMonitorLiveElapsedMinutes(payload) != null
}

fun oddsMonitorLiveScoreText(rawPayloadJson: String?): String? {
    val payload = rawPayloadJson?.takeIf { it.isNotBlank() } ?: return null
    val score = scorePattern.find(payload)
        ?.groupValues
        ?.getOrNull(1)
        ?.trim()
        ?.takeIf { it.isNotBlank() && it != "-" }
    if (score != null) {
        return score
    }

    val homeScore = homeScorePattern.find(payload)?.groupValues?.getOrNull(1)
    val awayScore = awayScorePattern.find(payload)?.groupValues?.getOrNull(1)
    return if (homeScore != null && awayScore != null) {
        "$homeScore-$awayScore"
    } else {
        null
    }
}

fun oddsMonitorLiveElapsedMinutes(rawPayloadJson: String?): Int? {
    val payload = rawPayloadJson?.takeIf { it.isNotBlank() } ?: return null
    elapsedNumberPattern.find(payload)
        ?.groupValues
        ?.getOrNull(1)
        ?.toIntOrNull()
        ?.let { return it }

    val elapsedText = elapsedTextPattern.find(payload)?.groupValues?.getOrNull(1)
    return normalizeOddsMonitorLiveElapsedMinutes(elapsedText)
}

fun normalizeOddsMonitorLiveElapsedMinutes(value: String?): Int? {
    val text = value?.trim()?.takeIf { it.isNotBlank() } ?: return null
    Regex("""^(\d{1,3})\s*\+\s*(\d{1,2})$""").find(text)?.let { match ->
        val base = match.groupValues[1].toIntOrNull() ?: return null
        val extra = match.groupValues[2].toIntOrNull() ?: return null
        return base + extra
    }
    Regex("""^(\d{1,3}):\d{1,2}$""").find(text)?.let { match ->
        return match.groupValues[1].toIntOrNull()
    }
    return Regex("""\d{1,3}""").find(text)?.value?.toIntOrNull()
}
