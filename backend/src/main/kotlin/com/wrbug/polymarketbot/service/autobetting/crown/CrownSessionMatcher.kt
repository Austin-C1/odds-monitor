package com.wrbug.polymarketbot.service.autobetting.crown

import com.wrbug.polymarketbot.dto.AdsPowerCrownSessionCandidateDto
import com.wrbug.polymarketbot.dto.AdsPowerCrownSessionDto
import com.wrbug.polymarketbot.service.autobetting.adspower.AdsPowerCdpClient
import com.wrbug.polymarketbot.service.autobetting.adspower.AdsPowerLocalApiClient
import com.wrbug.polymarketbot.service.autobetting.adspower.AdsPowerProfileMetadata
import com.wrbug.polymarketbot.service.autobetting.adspower.CrownPageSnapshot
import com.wrbug.polymarketbot.util.TextEncodingUtils
import java.math.BigDecimal

internal const val CROWN_NETWORK_UNSTABLE_STATUS = "crown_network_unstable"

class CrownSessionMatcher(
    private val apiClient: AdsPowerLocalApiClient,
    private val cdpClient: AdsPowerCdpClient
) {
    fun checkCrownSession(
        profileId: String,
        loginUrl: String? = null,
        loginName: String? = null,
        now: Long = System.currentTimeMillis()
    ): AdsPowerCrownSessionDto {
        val active = apiClient.checkProfileActive(profileId, now)
        if (!active.opened) {
            val isClosed = active.status?.equals("Inactive", ignoreCase = true) == true
            return AdsPowerCrownSessionDto(
                profileId = active.profileId,
                opened = false,
                loggedIn = false,
                accountStatus = if (isClosed) "profile_closed" else "profile_error",
                message = if (isClosed) "AdsPower 环境未打开" else active.message,
                debugPort = active.debugPort,
                checkedAt = now
            )
        }

        val debugPort = active.debugPort?.trim().orEmpty()
        if (debugPort.isBlank()) {
            return AdsPowerCrownSessionDto(
                profileId = active.profileId,
                opened = true,
                loggedIn = false,
                accountStatus = "browser_debug_port_missing",
                message = "AdsPower 未返回调试端口",
                checkedAt = now
            )
        }

        val normalizedLoginName = loginName?.trim().orEmpty()
        val analyzedSnapshot = cdpClient.readCrownPageSnapshots(debugPort, loginUrl)
            .map { CrownAnalyzedSnapshot(it, CrownSessionPageAnalyzer.analyze(it.text, it.title)) }
            .selectForLoginName(normalizedLoginName)
            ?: return AdsPowerCrownSessionDto(
                profileId = active.profileId,
                opened = true,
                loggedIn = false,
                accountStatus = "crown_page_not_found",
                message = "未找到皇冠页面",
                debugPort = debugPort,
                checkedAt = now
            )
        val snapshot = analyzedSnapshot.snapshot
        val analysis = analyzedSnapshot.analysis
        if (analysis.loggedIn && normalizedLoginName.isNotBlank() && analysis.loginName.conflictsWithLoginName(normalizedLoginName)) {
            return AdsPowerCrownSessionDto(
                profileId = active.profileId,
                opened = true,
                loggedIn = false,
                accountStatus = "crown_account_mismatch",
                pageUrl = snapshot.pageUrl,
                message = "浏览器里登录的是 ${analysis.loginName}，不是 $normalizedLoginName",
                debugPort = debugPort,
                checkedAt = now
            )
        }
        return AdsPowerCrownSessionDto(
            profileId = active.profileId,
            opened = true,
            loggedIn = analysis.loggedIn,
            accountStatus = analysis.accountStatus,
            balance = analysis.balance,
            currency = analysis.currency,
            pageUrl = snapshot.pageUrl,
            message = analysis.message,
            debugPort = debugPort,
            checkedAt = now
        )
    }

    fun matchCrownSession(
        loginName: String,
        loginUrl: String? = null,
        preferredProfileId: String? = null,
        now: Long = System.currentTimeMillis()
    ): AdsPowerCrownSessionDto {
        val normalizedLoginName = loginName.trim()
        val normalizedPreferredProfileId = preferredProfileId?.trim()?.takeIf { it.isNotBlank() }
        if (normalizedLoginName.isBlank()) {
            return AdsPowerCrownSessionDto(
                profileId = "",
                opened = false,
                loggedIn = false,
                accountStatus = "login_name_required",
                message = "login_name_required",
                checkedAt = now
            )
        }
        val activeProfiles = apiClient.listLocalActiveProfiles(now)
        if (activeProfiles.isEmpty()) {
            val profileCandidates = apiClient.loadProfileMetadataPage()
                .flatMap { profile -> buildCandidatesFromProfileMetadata(profile, loginUrl, now) }
            val matched = AdsPowerCrownProfileMatcher.match(normalizedLoginName, profileCandidates, normalizedPreferredProfileId)
                ?: profileCandidates.singleOrNull {
                    it.opened &&
                        it.hasConfiguredLoginEvidence(normalizedLoginName) &&
                        !it.pageLoginName.conflictsWithLoginName(normalizedLoginName)
                }
            if (matched != null) {
                return matched.toSessionDto(now)
            }
            return noMatchedCrownSession(normalizedLoginName, profileCandidates, now)
        }
        val metadata = apiClient.loadProfileMetadata(activeProfiles.map { it.profileId })
        val candidates = activeProfiles.flatMap { active ->
            val profile = metadata[active.profileId]
            val snapshots = active.debugPort?.let { cdpClient.readCrownPageSnapshots(it, loginUrl) }.orEmpty()
            if (snapshots.isEmpty()) {
                listOf(buildCrownSessionCandidate(active.profileId, profile, active.debugPort, null, null, now))
            } else {
                snapshots.map { snapshot ->
                    buildCrownSessionCandidate(
                        profileId = active.profileId,
                        profile = profile,
                        debugPort = active.debugPort,
                        snapshot = snapshot,
                        analysis = CrownSessionPageAnalyzer.analyze(snapshot.text, snapshot.title),
                        now = now
                    )
                }
            }
        }
        val matched = AdsPowerCrownProfileMatcher.match(normalizedLoginName, candidates, normalizedPreferredProfileId)
        if (matched != null) {
            return AdsPowerCrownSessionDto(
                profileId = matched.profileId,
                opened = matched.opened,
                loggedIn = matched.loggedIn,
                accountStatus = matched.accountStatus,
                balance = matched.balance,
                currency = matched.currency,
                pageUrl = matched.pageUrl,
                message = matched.message,
                debugPort = matched.debugPort,
                checkedAt = now
            )
        }
        return noMatchedCrownSession(normalizedLoginName, candidates, now)
    }

    private fun noMatchedCrownSession(
        loginName: String,
        candidates: List<AdsPowerCrownSessionCandidateDto>,
        now: Long
    ): AdsPowerCrownSessionDto {
        val openedCandidates = candidates.filter { it.opened }
        val loggedInCandidates = candidates.filter { it.opened && it.loggedIn }
        val loggedInCount = loggedInCandidates.size
        val knownLoginNames = loggedInCandidates
            .flatMap { candidate ->
                listOf(candidate.pageLoginName, candidate.profileUsername, candidate.profileName)
                    .mapNotNull { it.asConfiguredLoginName() }
            }
            .distinctBy(::normalizeProfileMatchText)
        val mismatchedLoginNames = knownLoginNames.filter { it.conflictsWithLoginName(loginName) }
        val status = when {
            loggedInCount == 0 -> "no_logged_in_crown_profile"
            mismatchedLoginNames.isNotEmpty() -> "no_matching_crown_profile"
            loggedInCount > 1 -> "ambiguous_crown_profile"
            else -> "no_matching_crown_profile"
        }
        val message = if (mismatchedLoginNames.isNotEmpty()) {
            "已检查所有已打开环境，未找到 $loginName；已登录账号：${mismatchedLoginNames.joinToString("、")}"
        } else if (loggedInCount > 0) {
            "已检查所有已打开环境，未找到 $loginName；有环境已登录，但没有读取到匹配的登录账号"
        } else {
            status
        }
        return AdsPowerCrownSessionDto(
            profileId = "",
            opened = openedCandidates.isNotEmpty(),
            loggedIn = false,
            accountStatus = status,
            message = message,
            debugPort = openedCandidates.firstOrNull()?.debugPort,
            checkedAt = now
        )
    }

    private fun buildCandidatesFromProfileMetadata(
        profile: AdsPowerProfileMetadata,
        loginUrl: String?,
        now: Long
    ): List<AdsPowerCrownSessionCandidateDto> {
        val activeByProfileId = apiClient.checkProfileActive(profile.profileId, now)
        val active = if (activeByProfileId.opened) {
            activeByProfileId
        } else {
            profile.serialNumber?.let { apiClient.checkProfileActive(it, now) }?.takeIf { it.opened }
        } ?: return emptyList()
        val snapshots = active.debugPort?.let { cdpClient.readCrownPageSnapshots(it, loginUrl) }.orEmpty()
        if (snapshots.isEmpty()) {
            return listOf(buildCrownSessionCandidate(profile.profileId, profile, active.debugPort, null, null, now))
        }
        return snapshots.map { snapshot ->
            buildCrownSessionCandidate(
                profileId = profile.profileId,
                profile = profile,
                debugPort = active.debugPort,
                snapshot = snapshot,
                analysis = CrownSessionPageAnalyzer.analyze(snapshot.text, snapshot.title),
                now = now
            )
        }
    }

    private fun buildCrownSessionCandidate(
        profileId: String,
        profile: AdsPowerProfileMetadata?,
        debugPort: String?,
        snapshot: CrownPageSnapshot?,
        analysis: CrownSessionAnalysis?,
        now: Long
    ): AdsPowerCrownSessionCandidateDto {
        val fallbackStatus = if (debugPort.isNullOrBlank()) "browser_debug_port_missing" else "crown_page_not_found"
        return AdsPowerCrownSessionCandidateDto(
            profileId = profileId,
            profileSerialNumber = profile?.serialNumber,
            profileName = profile?.name,
            profileUsername = profile?.username,
            remark = profile?.remark,
            pageLoginName = analysis?.loginName,
            opened = true,
            loggedIn = analysis?.loggedIn == true,
            accountStatus = analysis?.accountStatus ?: fallbackStatus,
            balance = analysis?.balance,
            currency = analysis?.currency ?: "CNY",
            pageUrl = snapshot?.pageUrl,
            message = analysis?.message ?: fallbackStatus,
            debugPort = debugPort,
            checkedAt = now
        )
    }

    private fun AdsPowerCrownSessionCandidateDto.toSessionDto(now: Long): AdsPowerCrownSessionDto {
        return AdsPowerCrownSessionDto(
            profileId = profileId,
            opened = opened,
            loggedIn = loggedIn,
            accountStatus = accountStatus,
            balance = balance,
            currency = currency,
            pageUrl = pageUrl,
            message = message,
            debugPort = debugPort,
            checkedAt = now
        )
    }

    private fun AdsPowerProfileMetadata.matchesLoginName(loginName: String): Boolean {
        val normalizedLoginName = normalizeProfileMatchText(loginName)
        return listOf(username, name, remark)
            .map(::normalizeProfileMatchText)
            .any { value -> value == normalizedLoginName || value.contains(normalizedLoginName) }
    }

    private fun AdsPowerCrownSessionCandidateDto.hasConfiguredLoginEvidence(loginName: String): Boolean {
        val normalizedLoginName = normalizeProfileMatchText(loginName)
        return normalizedLoginName.isNotBlank() &&
            listOf(profileUsername, profileName, remark)
                .map(::normalizeProfileMatchText)
                .any { value -> value == normalizedLoginName || value.contains(normalizedLoginName) }
    }

    private fun List<CrownAnalyzedSnapshot>.selectForLoginName(loginName: String): CrownAnalyzedSnapshot? {
        val normalizedLoginName = normalizeProfileMatchText(loginName)
        if (normalizedLoginName.isNotBlank()) {
            firstOrNull { snapshot ->
                snapshot.analysis.loggedIn &&
                    normalizeProfileMatchText(snapshot.analysis.loginName) == normalizedLoginName
            }?.let { return it }
        }
        return firstOrNull { it.analysis.loggedIn } ?: firstOrNull()
    }

    private fun normalizeProfileMatchText(value: String?): String {
        return value.orEmpty()
            .trim()
            .lowercase()
            .replace(Regex("""\s+"""), "")
    }

    private fun String?.conflictsWithLoginName(loginName: String): Boolean {
        val normalizedValue = normalizeProfileMatchText(this)
        return normalizedValue.isNotBlank() && normalizedValue != normalizeProfileMatchText(loginName)
    }

    private fun String?.asConfiguredLoginName(): String? {
        val candidate = this?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return candidate.takeIf { it.matches(Regex("""(?i)[a-z0-9][a-z0-9._-]{2,31}""")) }
    }

    private data class CrownAnalyzedSnapshot(
        val snapshot: CrownPageSnapshot,
        val analysis: CrownSessionAnalysis
    )
}

internal object AdsPowerCrownProfileMatcher {
    fun match(
        loginName: String,
        candidates: List<AdsPowerCrownSessionCandidateDto>,
        preferredProfileId: String? = null
    ): AdsPowerCrownSessionCandidateDto? {
        val usable = candidates.filter { it.opened && it.loggedIn }
        if (usable.isEmpty()) return null
        val normalizedLoginName = normalize(loginName)
        val normalizedPreferredProfileId = normalize(preferredProfileId)
        val pageMatches = usable.filter { candidate ->
            normalize(candidate.pageLoginName) == normalizedLoginName
        }
        if (pageMatches.size == 1) {
            return pageMatches.first()
        }
        if (pageMatches.size > 1) {
            return null
        }
        val nonConflicting = usable.filter { candidate ->
            val pageLoginName = normalize(candidate.pageLoginName)
            pageLoginName.isBlank() || pageLoginName == normalizedLoginName
        }
        val exactMatches = nonConflicting.filter { candidate ->
            listOf(candidate.profileUsername, candidate.profileName, candidate.remark)
                .map(::normalize)
                .any { value -> value == normalizedLoginName || value.contains(normalizedLoginName) }
        }
        if (exactMatches.size == 1) {
            return exactMatches.first()
        }
        if (exactMatches.size > 1) {
            return null
        }
        nonConflicting.firstOrNull { candidate ->
            normalizedPreferredProfileId.isNotBlank() &&
                listOf(candidate.profileId, candidate.profileSerialNumber)
                    .map(::normalize)
                    .any { it == normalizedPreferredProfileId }
        }?.let { return it }
        return null
    }

    private fun normalize(value: String?): String {
        return value.orEmpty()
            .trim()
            .lowercase()
            .replace(Regex("""\s+"""), "")
    }
}

internal data class CrownSessionAnalysis(
    val loggedIn: Boolean,
    val accountStatus: String,
    val balance: BigDecimal?,
    val loginName: String? = null,
    val currency: String = "CNY",
    val message: String
)

internal object CrownSessionPageAnalyzer {
    private val balancePatterns = listOf(
        Regex("""(?i)RMB\s*([0-9][0-9,]*(?:\.\d{1,2})?)"""),
        Regex("""[¥￥]\s*([0-9][0-9,]*(?:\.\d{1,2})?)"""),
        Regex("""(?:余额|账户余额|可用余额|信用额度|额度)\s*[:：]?\s*(?:RMB|[¥￥])?\s*([0-9][0-9,]*(?:\.\d{1,2})?)""")
    )
    private val loginFormPattern = Regex("""(?:登录|登入|Login).{0,80}(?:密码|Password)""", RegexOption.IGNORE_CASE)
    private val abnormalPattern = Regex("""账号(?:异常|停用|冻结|锁定)|账户(?:异常|停用|冻结|锁定)|封号|被锁定""")
    private val networkUnstablePattern = Regex(
        """网络不稳定|網絡不穩定|网络异常|網絡異常|重新更新|重新整理|network.{0,24}(?:unstable|error)|please.{0,24}(?:refresh|reload)""",
        RegexOption.IGNORE_CASE
    )
    private val loggedInMenuMarkers = listOf(
        "账户历史",
        "账户安全",
        "修改密码",
        "投注记录",
        "我的赛事",
        "讯息",
        "消息"
    )

    fun analyze(text: String, pageTitle: String? = null): CrownSessionAnalysis {
        val rawNormalized = text.replace('\u00A0', ' ').trim()
        if (rawNormalized.isBlank()) {
            return CrownSessionAnalysis(false, "unknown", null, message = "皇冠页面未返回内容")
        }
        val repaired = TextEncodingUtils.repairMojibake(rawNormalized)
        val normalized = listOf(rawNormalized, repaired).distinct().joinToString("\n")
        if (networkUnstablePattern.containsMatchIn(normalized)) {
            return CrownSessionAnalysis(false, CROWN_NETWORK_UNSTABLE_STATUS, null, message = "皇冠网络不稳定，请刷新后重试")
        }
        if (abnormalPattern.containsMatchIn(normalized)) {
            return CrownSessionAnalysis(false, "abnormal", null, message = "账号异常")
        }
        val loginName = extractLoginName(normalized, pageTitle)
        val balance = extractBalance(normalized)
        if (balance != null) {
            return CrownSessionAnalysis(true, "online", balance, loginName = loginName, message = "账号在线，余额已获取")
        }
        if (loginFormPattern.containsMatchIn(normalized)) {
            return CrownSessionAnalysis(false, "login_required", null, message = "皇冠未登录")
        }
        if (hasLoggedInMenu(normalized)) {
            return CrownSessionAnalysis(true, "online", null, loginName = loginName, message = "账号在线，余额未读取到")
        }
        return CrownSessionAnalysis(false, "unknown", null, message = "未识别到登录状态和余额")
    }

    private fun extractBalance(text: String): BigDecimal? {
        for (pattern in balancePatterns) {
            val value = pattern.find(text)?.groupValues?.getOrNull(1) ?: continue
            return runCatching { BigDecimal(value.replace(",", "")) }.getOrNull()
        }
        return null
    }

    private fun hasLoggedInMenu(text: String): Boolean {
        val markerCount = loggedInMenuMarkers.count { marker -> text.contains(marker) }
        return markerCount >= 2
    }

    private fun extractLoginName(text: String, pageTitle: String?): String? {
        val compactHeaderLoginName = Regex("""(?i)([a-z0-9][a-z0-9._-]{2,31})\s*RMB\s*[0-9]""")
            .find(text)
            ?.groupValues
            ?.getOrNull(1)
        normalizeLoginCandidate(compactHeaderLoginName)?.let { return it }

        val labeledLoginName = Regex("""(?i)(?:登录账号|会员账号|账号|账户|username|login\s*name|account)\s*[:：]?\s*([a-z0-9][a-z0-9._-]{2,31})""")
            .find(text)
            ?.groupValues
            ?.getOrNull(1)
        normalizeLoginCandidate(labeledLoginName)?.let { return it }

        val markerStart = loggedInMenuMarkers
            .map { marker -> text.indexOf(marker).takeIf { it >= 0 } ?: Int.MAX_VALUE }
            .minOrNull()
            ?: Int.MAX_VALUE
        if (markerStart == Int.MAX_VALUE) {
            return normalizeLoginCandidate(pageTitle)
        }

        val header = text.take(markerStart)
        return Regex("""(?i)\b[a-z0-9][a-z0-9._-]{2,31}\b""")
            .findAll(header)
            .map { it.value }
            .firstNotNullOfOrNull(::normalizeLoginCandidate)
            ?: normalizeLoginCandidate(pageTitle)
    }

    private fun normalizeLoginCandidate(value: String?): String? {
        val candidate = value
            ?.trim()
            ?.trim('-', '_', '.', ':', '：')
            ?.takeIf { it.isNotBlank() }
            ?: return null
        if (!candidate.matches(Regex("""(?i)[a-z0-9][a-z0-9._-]{2,31}"""))) return null
        if (!candidate.containsAsciiLetter()) return null
        val normalized = candidate.lowercase()
        if (normalized.startsWith("gmt")) return null
        if (normalized in nonLoginTokens) return null
        return candidate
    }

    private fun String.containsAsciiLetter(): Boolean {
        return any { char -> char in 'a'..'z' || char in 'A'..'Z' }
    }

    private val nonLoginTokens = setOf(
        "rmb",
        "cny",
        "welcome",
        "login",
        "password",
        "account",
        "username",
        "in-play",
        "hot",
        "today",
        "soon",
        "early",
        "outrights",
        "parlay",
        "events",
        "bets",
        "sports",
        "soccer",
        "basketball",
        "football",
        "tennis",
        "volleyball",
        "esports",
        "system",
        "language",
        "phone",
        "email"
    )
}
