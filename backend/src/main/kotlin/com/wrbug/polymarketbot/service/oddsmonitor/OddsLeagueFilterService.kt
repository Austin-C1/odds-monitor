package com.wrbug.polymarketbot.service.oddsmonitor

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.wrbug.polymarketbot.entity.OddsPlatformMatch
import com.wrbug.polymarketbot.entity.SystemConfig
import com.wrbug.polymarketbot.repository.SystemConfigRepository
import com.wrbug.polymarketbot.util.TextEncodingUtils
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class OddsLeagueFilterService(
    private val systemConfigRepository: SystemConfigRepository,
    private val objectMapper: ObjectMapper = ObjectMapper()
) {
    fun getSelectedLeagues(): List<String> {
        val rawValue = systemConfigRepository.findByConfigKey(CONFIG_KEY)?.configValue
            ?.takeIf { it.isNotBlank() }
            ?: return emptyList()
        return runCatching {
            objectMapper.readValue(rawValue, object : TypeReference<List<String>>() {})
        }.getOrDefault(emptyList())
            .mapNotNull { normalizeLeagueName(it) }
            .distinct()
    }

    @Transactional
    fun saveSelectedLeagues(leagues: List<String>): List<String> {
        val normalized = leagues.mapNotNull { normalizeLeagueName(it) }.distinct()
        val json = objectMapper.writeValueAsString(normalized)
        val now = System.currentTimeMillis()
        val existing = systemConfigRepository.findByConfigKey(CONFIG_KEY)
        val entity = existing?.copy(configValue = json, updatedAt = now) ?: SystemConfig(
            configKey = CONFIG_KEY,
            configValue = json,
            description = "全平台赔率监控联赛筛选",
            createdAt = now,
            updatedAt = now
        )
        systemConfigRepository.save(entity)
        return normalized
    }

    fun shouldIncludeLeague(leagueName: String): Boolean {
        val selected = getSelectedLeagues()
        if (selected.isEmpty()) {
            return true
        }
        val normalized = normalizeLeagueName(leagueName) ?: return false
        return normalized in selected
    }

    companion object {
        const val CONFIG_KEY = "odds_monitor.selected_leagues"
    }
}

fun availableOddsLeagueNames(matches: List<OddsPlatformMatch>): List<String> {
    return matches
        .mapNotNull { normalizeLeagueName(it.rawLeagueName) }
        .distinct()
        .sortedWith(compareBy<String> { it.any { char -> char.code < 128 } }.thenBy { it })
}

private fun normalizeLeagueName(value: String?): String? {
    val repaired = TextEncodingUtils.repairMojibake(value.orEmpty())
        .replace(Regex("\\s+"), " ")
        .trim()
        .takeIf { it.isNotBlank() }
        ?: return null

    return canonicalLeagueAliases[leagueAliasKey(repaired)]
        ?: repaired
            .replace('－', '-')
            .replace('–', '-')
            .replace('—', '-')
            .replace(Regex("\\s*-\\s*"), "-")
            .replace(Regex("-(特别投注|附加赛|赛中盘|滚球|优胜冠军)$"), "")
            .replace(Regex("([\\p{IsHan}])\\s+([\\p{IsHan}A-Za-z0-9])"), "$1$2")
            .replace(Regex("([A-Za-z0-9])\\s+([\\p{IsHan}])"), "$1$2")
            .replace(Regex("^([\\p{IsHan}]{2,6})-(.+)$")) { match ->
                val country = match.groupValues[1]
                val league = match.groupValues[2]
                if (league.any { it.code > 127 }) "$country$league" else match.value
            }
            .let { canonicalLeagueAliases[leagueAliasKey(it)] ?: it }
            .trim()
            .takeIf { it.isNotBlank() }
}

private fun leagueAliasKey(value: String): String {
    return value
        .lowercase()
        .replace(Regex("[\\s\\-_/()（）·.]+"), "")
}

private val canonicalLeagueAliases = mapOf(
    "英超" to "英格兰超级联赛",
    "英格兰超级联赛" to "英格兰超级联赛",
    "englandpremierleague" to "英格兰超级联赛",
    "englishpremierleague" to "英格兰超级联赛",
    "premierleague" to "英格兰超级联赛",
    "日本j1" to "日本J1",
    "日本j1联赛" to "日本J1",
    "japanj1league" to "日本J1",
    "j1league" to "日本J1"
)
