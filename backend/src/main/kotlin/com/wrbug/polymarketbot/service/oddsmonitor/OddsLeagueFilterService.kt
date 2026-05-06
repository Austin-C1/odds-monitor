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
    fun getSelectedLeagues(sourceKey: String? = null): List<String> {
        val normalizedSourceKey = normalizeLeagueFilterSourceKey(sourceKey)
        val config = systemConfigRepository.findByConfigKey(configKey(normalizedSourceKey))
        val rawValue = config?.configValue?.takeIf { it.isNotBlank() }
        if (rawValue == null) {
            return if (normalizedSourceKey == null) defaultTrackedLeagueNames() else emptyList()
        }

        val parsed = runCatching {
            objectMapper.readValue(rawValue, object : TypeReference<List<String>>() {})
        }.getOrDefault(emptyList())

        return if (normalizedSourceKey == null) {
            parsed.mapNotNull { canonicalOddsLeagueName(it) }.distinct()
        } else {
            parsed.mapNotNull { rawOddsLeagueName(it) }.distinct()
        }
    }

    @Transactional
    fun saveSelectedLeagues(leagues: List<String>, sourceKey: String? = null): List<String> {
        val normalizedSourceKey = normalizeLeagueFilterSourceKey(sourceKey)
        val normalized = if (normalizedSourceKey == null) {
            leagues.mapNotNull { canonicalOddsLeagueName(it) }.distinct()
        } else {
            leagues.mapNotNull { rawOddsLeagueName(it) }.distinct()
        }
        val json = objectMapper.writeValueAsString(normalized)
        val now = System.currentTimeMillis()
        val key = configKey(normalizedSourceKey)
        val existing = systemConfigRepository.findByConfigKey(key)
        val entity = existing?.copy(configValue = json, updatedAt = now) ?: SystemConfig(
            configKey = key,
            configValue = json,
            description = if (normalizedSourceKey == null) {
                "全平台赔率监控默认追踪联赛"
            } else {
                "全平台赔率监控${sourceDisplayName(normalizedSourceKey)}原始联赛筛选"
            },
            createdAt = now,
            updatedAt = now
        )
        systemConfigRepository.save(entity)
        return normalized
    }

    fun shouldIncludeLeague(leagueName: String): Boolean {
        if (isSpecialBettingLeague(leagueName)) {
            return false
        }
        val selected = getSelectedLeagues()
        val normalized = canonicalOddsLeagueName(leagueName) ?: return false
        return normalized in selected
    }

    fun shouldIncludeLeague(sourceKey: String?, leagueName: String): Boolean {
        if (isSpecialBettingLeague(leagueName)) {
            return false
        }
        val normalizedSourceKey = normalizeLeagueFilterSourceKey(sourceKey)
        if (normalizedSourceKey == null) {
            return shouldIncludeLeague(leagueName)
        }
        val config = systemConfigRepository.findByConfigKey(configKey(normalizedSourceKey))
        if (config == null) {
            return false
        }
        val selected = getSelectedLeagues(normalizedSourceKey)
        val normalized = rawOddsLeagueName(leagueName) ?: return false
        return normalized in selected
    }

    fun getDefaultTrackingLeagues(): List<String> {
        return (getSelectedLeagues(null) + getSelectedLeagues("pinnacle") + getSelectedLeagues("crown"))
            .distinct()
            .sortedWith(compareBy<String> { it.any { char -> char.code < 128 } }.thenBy { it })
    }

    fun hasSelectedLeaguesConfig(sourceKey: String?): Boolean {
        val normalizedSourceKey = normalizeLeagueFilterSourceKey(sourceKey)
        return systemConfigRepository.findByConfigKey(configKey(normalizedSourceKey)) != null
    }

    companion object {
        const val CONFIG_KEY = "odds_monitor.selected_leagues"
        const val PINNACLE_CONFIG_KEY = "odds_monitor.selected_leagues.pinnacle"
        const val CROWN_CONFIG_KEY = "odds_monitor.selected_leagues.crown"
    }

    private fun configKey(sourceKey: String?): String {
        return when (sourceKey) {
            "pinnacle" -> PINNACLE_CONFIG_KEY
            "crown" -> CROWN_CONFIG_KEY
            else -> CONFIG_KEY
        }
    }
}

fun availableOddsLeagueNames(matches: List<OddsPlatformMatch>, sourceKey: String? = null): List<String> {
    val normalizedSourceKey = normalizeLeagueFilterSourceKey(sourceKey)
    if (normalizedSourceKey != null) {
        return availableRawOddsLeagueNames(matches.filter { it.sourceKey == normalizedSourceKey })
    }
    return matches
        .filterNot { isSpecialBettingLeague(it.rawLeagueName) }
        .mapNotNull { canonicalOddsLeagueName(it.rawLeagueName) }
        .distinct()
        .sortedWith(compareBy<String> { it.any { char -> char.code < 128 } }.thenBy { it })
}

fun availableRawOddsLeagueNames(matches: List<OddsPlatformMatch>): List<String> {
    return matches
        .filterNot { isSpecialBettingLeague(it.rawLeagueName) }
        .mapNotNull { rawOddsLeagueName(it.rawLeagueName) }
        .distinct()
        .sortedWith(compareBy<String> { it.any { char -> char.code < 128 } }.thenBy { it })
}

fun defaultTrackedLeagueNames(): List<String> = defaultTrackedLeagues

fun rawOddsLeagueName(value: String?): String? {
    return TextEncodingUtils.repairMojibake(value.orEmpty())
        .replace(Regex("\\s+"), " ")
        .trim()
        .takeIf { it.isNotBlank() }
}

fun canonicalOddsLeagueName(value: String?): String? {
    val repaired = TextEncodingUtils.repairMojibake(value.orEmpty())
        .replace(Regex("\\s+"), " ")
        .trim()
        .takeIf { it.isNotBlank() }
        ?: return null

    val cleaned = repaired
        .replace('－', '-')
        .replace('–', '-')
        .replace('—', '-')
        .replace(Regex("\\s*-\\s*"), "-")
        .replace(Regex("-(特别投注|特別投注|附加赛|附加賽|Specials?|Special Betting|Play-?offs?|Play\\s+offs?)$", RegexOption.IGNORE_CASE), "")
        .replace(Regex("\\s+[a-z]$", RegexOption.IGNORE_CASE), "")
        .replace(Regex("([\\p{IsHan}])\\s+([\\p{IsHan}A-Za-z0-9])"), "$1$2")
        .replace(Regex("([A-Za-z0-9])\\s+([\\p{IsHan}])"), "$1$2")
        .replace(Regex("^([\\p{IsHan}]{2,8})-(.+)$")) { match ->
            val country = match.groupValues[1]
            val league = match.groupValues[2]
            if (league.any { it.code > 127 }) "$country$league" else match.value
        }
        .trim()

    return leagueAliases[leagueAliasKey(cleaned)]
        ?: cleaned.takeIf { it.isNotBlank() }
}

fun normalizeLeagueFilterSourceKey(sourceKey: String?): String? {
    return when (sourceKey?.trim()?.lowercase()) {
        "pinnacle" -> "pinnacle"
        "crown" -> "crown"
        else -> null
    }
}

private fun sourceDisplayName(sourceKey: String): String {
    return when (sourceKey) {
        "pinnacle" -> "平博"
        "crown" -> "皇冠"
        else -> sourceKey
    }
}

fun isSpecialBettingLeague(value: String?): Boolean {
    val text = TextEncodingUtils.repairMojibake(value.orEmpty()).lowercase()
    return listOf(
        "特别投注",
        "特別投注",
        "附加赛",
        "附加賽",
        "special betting",
        "specials",
        "playoff",
        "play-offs",
        "play off"
    ).any { text.contains(it) }
}

private fun leagueAliasKey(value: String): String {
    return value
        .lowercase()
        .replace(Regex("[\\s\\-_/()（）·.]+"), "")
}

private val defaultTrackedLeagues = listOf(
    "英格兰超级联赛",
    "英格兰冠军联赛",
    "英格兰甲组联赛",
    "英格兰乙组联赛",
    "德国甲组联赛",
    "德国乙组联赛",
    "西班牙甲组联赛",
    "西班牙乙组联赛",
    "意大利甲组联赛",
    "意大利乙组联赛",
    "法国甲组联赛",
    "法国乙组联赛",
    "荷兰甲组联赛",
    "荷兰乙组联赛",
    "葡萄牙超级联赛",
    "葡萄牙甲组联赛",
    "俄罗斯超级联赛",
    "挪威超级联赛",
    "芬兰超级联赛",
    "芬兰甲组联赛",
    "瑞典超级联赛",
    "瑞典超级甲组联赛",
    "丹麦超级联赛",
    "丹麦甲组联赛",
    "奥地利甲组联赛",
    "奥地利乙组联赛",
    "瑞士超级联赛",
    "瑞士甲组联赛",
    "爱尔兰超级联赛",
    "爱尔兰甲组联赛",
    "比利时甲组联赛A",
    "土耳其超级联赛",
    "希腊超级联赛甲组",
    "苏格兰超级联赛",
    "波兰超级联赛",
    "罗马尼亚甲组联赛",
    "捷克甲组联赛",
    "乌克兰超级联赛",
    "冰岛超级联赛",
    "阿根廷职业联赛",
    "巴西甲组联赛",
    "巴西乙组联赛",
    "智利甲组联赛",
    "哥伦比亚甲组联赛",
    "厄瓜多尔甲组联赛",
    "巴拉圭甲组联赛",
    "秘鲁甲组联赛",
    "美国职业大联盟",
    "美国足球冠军联赛",
    "墨西哥超级联赛",
    "墨西哥甲组联赛",
    "日本J1百年构想联赛",
    "日本J2 J3百年构想联赛",
    "澳大利亚甲组联赛",
    "澳大利亚维多利亚国家超级联赛",
    "澳大利亚女子甲组联赛",
    "韩国K甲组联赛",
    "沙特超级联赛",
    "卡塔尔甲组联赛",
    "阿联酋超级联赛",
    "巴林超级联赛",
    "印度超级联赛",
    "印尼超级联赛",
    "中国超级联赛",
    "埃及超级联赛",
    "欧洲冠军联赛",
    "欧洲联赛",
    "欧洲协会联赛",
    "英格兰足总杯",
    "英格兰联赛杯",
    "英格兰联赛锦标赛",
    "德国杯",
    "西班牙杯",
    "意大利杯",
    "法国杯",
    "荷兰KNVB杯",
    "葡萄牙杯",
    "俄罗斯杯",
    "丹麦杯",
    "挪威杯",
    "瑞典杯",
    "比利时杯",
    "奥地利杯",
    "土耳其杯",
    "波兰杯",
    "希腊杯",
    "苏格兰足总杯",
    "罗马尼亚杯",
    "捷克杯",
    "乌克兰杯",
    "冰岛超级杯",
    "冰岛联赛杯",
    "南美自由杯",
    "南美洲球会杯",
    "阿根廷杯",
    "巴西杯",
    "智利联赛杯",
    "中北美洲及加勒比海冠军杯",
    "美国公开赛冠军杯",
    "亚足联冠军精英联赛",
    "亚足联冠军联赛二",
    "澳大利亚杯",
    "澳大利亚杯外围赛",
    "沙特国王杯",
    "卡塔尔联赛杯",
    "阿联酋总统杯",
    "阿联酋足总杯",
    "巴林超级杯",
    "埃及联赛杯",
    "埃及杯",
    "世界杯2026(美加墨)",
    "世界杯2026洲际(在墨西哥)",
    "欧美杯2026(在卡塔尔)",
    "世界杯2026欧洲外围赛",
    "欧洲国家联赛",
    "非洲国家杯2027外围赛",
    "国际友谊赛",
    "国际系列"
)

private val rawLeagueAliases = mapOf(
    "英超" to "英格兰超级联赛",
    "英格兰-超级联赛" to "英格兰超级联赛",
    "英格兰-冠军联赛" to "英格兰冠军联赛",
    "英格兰-甲级联赛" to "英格兰甲组联赛",
    "英格兰-乙级联赛" to "英格兰乙组联赛",
    "德国-德甲" to "德国甲组联赛",
    "德国-德乙" to "德国乙组联赛",
    "西班牙-西甲" to "西班牙甲组联赛",
    "西班牙-乙级联赛" to "西班牙乙组联赛",
    "意大利-甲级联赛" to "意大利甲组联赛",
    "意大利-乙级联赛" to "意大利乙组联赛",
    "法国-甲级联赛" to "法国甲组联赛",
    "法国-乙级联赛" to "法国乙组联赛",
    "荷兰-甲级联赛" to "荷兰甲组联赛",
    "荷兰-乙级联赛" to "荷兰乙组联赛",
    "葡萄牙-超级联赛" to "葡萄牙超级联赛",
    "葡萄牙-甲级联赛" to "葡萄牙甲组联赛",
    "芬兰-足球超级联赛A" to "芬兰超级联赛",
    "芬兰-全国联赛" to "芬兰甲组联赛",
    "瑞典-超级联赛" to "瑞典超级联赛",
    "瑞典-甲级联赛" to "瑞典超级甲组联赛",
    "丹麦-超级联赛" to "丹麦超级联赛",
    "丹麦-甲级联赛" to "丹麦甲组联赛",
    "奥地利-甲级联赛" to "奥地利甲组联赛",
    "奥地利-乙级联赛" to "奥地利乙组联赛",
    "瑞士-超级联赛" to "瑞士超级联赛",
    "瑞士-挑战联赛" to "瑞士甲组联赛",
    "爱尔兰-甲级联赛" to "爱尔兰甲组联赛",
    "比利时-职业联赛" to "比利时甲组联赛A",
    "土耳其-超级联赛" to "土耳其超级联赛",
    "苏格兰-足球超级联赛" to "苏格兰超级联赛",
    "波兰-超级联赛" to "波兰超级联赛",
    "罗马尼亚-甲级联赛" to "罗马尼亚甲组联赛",
    "捷克-甲级联赛" to "捷克甲组联赛",
    "巴西-甲级联赛" to "巴西甲组联赛",
    "巴西-乙级联赛" to "巴西乙组联赛",
    "阿根廷-职业联赛" to "阿根廷职业联赛",
    "智利-甲级联赛" to "智利甲组联赛",
    "哥伦比亚-甲级联赛" to "哥伦比亚甲组联赛",
    "厄瓜多尔-甲级联赛" to "厄瓜多尔甲组联赛",
    "巴拉圭-职业联赛" to "巴拉圭甲组联赛",
    "秘鲁-足球甲级联赛" to "秘鲁甲组联赛",
    "美国-美国足球大联盟" to "美国职业大联盟",
    "美国-USL锦标赛" to "美国足球冠军联赛",
    "墨西哥-足球甲级联赛" to "墨西哥超级联赛",
    "墨西哥-墨西哥足球拓展联赛" to "墨西哥甲组联赛",
    "日本-J联赛" to "日本J1百年构想联赛",
    "Japan-J1League" to "日本J1百年构想联赛",
    "Japan-J2/J3League" to "日本J2 J3百年构想联赛",
    "澳大利亚-甲级联赛" to "澳大利亚甲组联赛",
    "澳大利亚-NPL维多利亚州" to "澳大利亚维多利亚国家超级联赛",
    "澳大利亚-甲级联赛女子" to "澳大利亚女子甲组联赛",
    "韩国-K联赛1" to "韩国K甲组联赛",
    "沙特阿拉伯-职业联赛" to "沙特超级联赛",
    "阿联酋-职业联赛" to "阿联酋超级联赛",
    "中国-超级联赛" to "中国超级联赛",
    "印度尼西亚-超级联赛" to "印尼超级联赛",
    "欧足联-冠军联赛" to "欧洲冠军联赛",
    "欧足联-欧罗巴联赛" to "欧洲联赛",
    "欧足联-欧洲协会联赛" to "欧洲协会联赛",
    "英格兰足总杯" to "英格兰足总杯",
    "德国杯赛" to "德国杯",
    "意大利-杯赛" to "意大利杯",
    "芬兰-杯赛" to "芬兰杯",
    "奥地利-杯赛" to "奥地利杯",
    "波兰-杯赛" to "波兰杯",
    "南美足联-解放者杯" to "南美自由杯",
    "南美足协-南美俱乐部杯" to "南美洲球会杯",
    "阿根廷-杯赛" to "阿根廷杯",
    "美国-公开杯" to "美国公开赛冠军杯",
    "FIFA-世界杯" to "世界杯2026(美加墨)",
    "EnglandPremierLeague" to "英格兰超级联赛",
    "EnglishPremierLeague" to "英格兰超级联赛",
    "PremierLeague" to "英格兰超级联赛",
    "UEFAChampionsLeague" to "欧洲冠军联赛",
    "UEFAEuropaLeague" to "欧洲联赛",
    "UEFAConferenceLeague" to "欧洲协会联赛",
    "MLS" to "美国职业大联盟",
    "MajorLeagueSoccer" to "美国职业大联盟"
)

private val leagueAliases = (defaultTrackedLeagues.associateBy { leagueAliasKey(it) } +
    rawLeagueAliases.entries.associate { leagueAliasKey(it.key) to it.value })
