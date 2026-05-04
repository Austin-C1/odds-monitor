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
    return TextEncodingUtils.repairMojibake(value.orEmpty())
        .replace(Regex("\\s+"), " ")
        .trim()
        .takeIf { it.isNotBlank() }
}
