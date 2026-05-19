package com.wrbug.polymarketbot.service.autobetting

import com.fasterxml.jackson.databind.ObjectMapper
import com.wrbug.polymarketbot.dto.AdsPowerBrowserSessionDto
import com.wrbug.polymarketbot.dto.AdsPowerCrownSessionCandidateDto
import com.wrbug.polymarketbot.dto.AdsPowerCrownSessionDto
import com.wrbug.polymarketbot.dto.AdsPowerProfileActiveDto
import com.wrbug.polymarketbot.dto.AdsPowerStatusDto
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.net.URI
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@Service
class AdsPowerLocalApiService(
    private val objectMapper: ObjectMapper,
    @Value("\${adspower.local-api-url:\${adspower.local.api.url:http://127.0.0.1:50325}}")
    private val baseUrl: String = DEFAULT_BASE_URL,
    @Value("\${adspower.api-key:\${adspower.api.key:}}")
    private val apiKey: String? = null
) : CrownBetPlacementGateway {
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(Duration.ofSeconds(3))
        .readTimeout(Duration.ofSeconds(8))
        .writeTimeout(Duration.ofSeconds(8))
        .build()

    fun checkStatus(now: Long = System.currentTimeMillis()): AdsPowerStatusDto {
        val endpoint = buildUrl("/status") ?: return AdsPowerStatusDto(
            available = false,
            baseUrl = normalizedBaseUrl(),
            message = "invalid_adspower_base_url",
            checkedAt = now
        )
        val request = requestBuilder(endpoint).get().build()

        return try {
            httpClient.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                val root = body.takeIf { it.isNotBlank() }?.let { objectMapper.readTree(it) }
                val code = root?.path("code")?.takeIf { !it.isMissingNode && !it.isNull }?.asInt()
                val message = root?.path("msg")?.takeIf { !it.isMissingNode && !it.isNull }?.asText()
                    ?: if (response.isSuccessful) "success" else "http_${response.code}"
                AdsPowerStatusDto(
                    available = response.isSuccessful && code == 0,
                    baseUrl = normalizedBaseUrl(),
                    code = code,
                    message = message,
                    checkedAt = now
                )
            }
        } catch (error: Exception) {
            AdsPowerStatusDto(
                available = false,
                baseUrl = normalizedBaseUrl(),
                code = null,
                message = error.message ?: "adspower_unreachable",
                checkedAt = now
            )
        }
    }

    fun startProfile(profileId: String, now: Long = System.currentTimeMillis()): AdsPowerBrowserSessionDto {
        val normalizedProfileId = profileId.trim()
        if (normalizedProfileId.isBlank()) {
            return AdsPowerBrowserSessionDto(
                profileId = "",
                opened = false,
                message = "profile_id_required",
                openedAt = now
            )
        }

        val byUserId = startProfileBy("user_id", normalizedProfileId, normalizedProfileId, now)
        if (byUserId.opened || !normalizedProfileId.isAdsPowerSerialNumber()) {
            return byUserId
        }
        val bySerialNumber = startProfileBy("serial_number", normalizedProfileId, normalizedProfileId, now)
        return if (bySerialNumber.opened) bySerialNumber else byUserId
    }

    fun checkProfileActive(profileId: String, now: Long = System.currentTimeMillis()): AdsPowerProfileActiveDto {
        val normalizedProfileId = profileId.trim()
        if (normalizedProfileId.isBlank()) {
            return AdsPowerProfileActiveDto(
                profileId = "",
                opened = false,
                message = "profile_id_required",
                checkedAt = now
            )
        }

        val byUserId = checkProfileActiveBy("user_id", normalizedProfileId, normalizedProfileId, now)
        if (byUserId.opened || !normalizedProfileId.isAdsPowerSerialNumber()) {
            return byUserId
        }
        val bySerialNumber = checkProfileActiveBy("serial_number", normalizedProfileId, normalizedProfileId, now)
        return if (bySerialNumber.opened) bySerialNumber else byUserId
    }

    private fun startProfileBy(
        parameterName: String,
        parameterValue: String,
        profileId: String,
        now: Long
    ): AdsPowerBrowserSessionDto {
        val endpoint = buildUrl("/api/v1/browser/start")
            ?.newBuilder()
            ?.addQueryParameter(parameterName, parameterValue)
            ?.addQueryParameter("open_tabs", "1")
            ?.addQueryParameter("ip_tab", "1")
            ?.addQueryParameter("headless", "0")
            ?.build()
            ?: return AdsPowerBrowserSessionDto(
                profileId = profileId,
                opened = false,
                message = "invalid_adspower_base_url",
                openedAt = now
            )
        val request = requestBuilder(endpoint).get().build()

        return try {
            httpClient.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                val root = body.takeIf { it.isNotBlank() }?.let { objectMapper.readTree(it) }
                val code = root?.path("code")?.takeIf { !it.isMissingNode && !it.isNull }?.asInt()
                val message = root?.path("msg")?.takeIf { !it.isMissingNode && !it.isNull }?.asText()
                    ?: if (response.isSuccessful) "success" else "http_${response.code}"
                val data = root?.path("data")
                AdsPowerBrowserSessionDto(
                    profileId = profileId,
                    opened = response.isSuccessful && code == 0,
                    message = message,
                    debugPort = data.debugPortOrNull(),
                    openedAt = now
                )
            }
        } catch (error: Exception) {
            AdsPowerBrowserSessionDto(
                profileId = profileId,
                opened = false,
                message = error.message ?: "adspower_start_failed",
                openedAt = now
            )
        }
    }

    private fun checkProfileActiveBy(
        parameterName: String,
        parameterValue: String,
        profileId: String,
        now: Long
    ): AdsPowerProfileActiveDto {
        val endpoint = buildUrl("/api/v1/browser/active")
            ?.newBuilder()
            ?.addQueryParameter(parameterName, parameterValue)
            ?.build()
            ?: return AdsPowerProfileActiveDto(
                profileId = profileId,
                opened = false,
                message = "invalid_adspower_base_url",
                checkedAt = now
            )
        val request = requestBuilder(endpoint).get().build()

        return try {
            httpClient.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                val root = body.takeIf { it.isNotBlank() }?.let { objectMapper.readTree(it) }
                val code = root?.path("code")?.takeIf { !it.isMissingNode && !it.isNull }?.asInt()
                val message = root?.path("msg")?.takeIf { !it.isMissingNode && !it.isNull }?.asText()
                    ?: if (response.isSuccessful) "success" else "http_${response.code}"
                val data = root?.path("data")
                val status = data?.path("status")?.textOrNull()
                AdsPowerProfileActiveDto(
                    profileId = profileId,
                    opened = response.isSuccessful && code == 0 && status?.equals("Active", ignoreCase = true) == true,
                    message = message,
                    status = status,
                    debugPort = data.debugPortOrNull(),
                    checkedAt = now
                )
            }
        } catch (error: Exception) {
            AdsPowerProfileActiveDto(
                profileId = profileId,
                opened = false,
                message = error.message ?: "adspower_profile_active_failed",
                checkedAt = now
            )
        }
    }

    fun checkCrownSession(
        profileId: String,
        loginUrl: String? = null,
        loginName: String? = null,
        now: Long = System.currentTimeMillis()
    ): AdsPowerCrownSessionDto {
        val active = checkProfileActive(profileId, now)
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
        val analyzedSnapshot = readCrownPageSnapshots(debugPort, loginUrl)
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
        val activeProfiles = listLocalActiveProfiles(now)
        if (activeProfiles.isEmpty()) {
            val profileCandidates = loadProfileMetadataPage()
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
        val metadata = loadProfileMetadata(activeProfiles.map { it.profileId })
        val candidates = activeProfiles.flatMap { active ->
            val profile = metadata[active.profileId]
            val snapshots = active.debugPort?.let { readCrownPageSnapshots(it, loginUrl) }.orEmpty()
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
            opened = loggedInCount > 0,
            loggedIn = false,
            accountStatus = status,
            message = message,
            checkedAt = now
        )
    }

    override fun placeBet(command: CrownBetPlacementCommand): CrownBetPlacementResult {
        val active = checkProfileActive(command.profileId)
        if (!active.opened) {
            return CrownBetPlacementResult(false, false, null, "profile_not_opened")
        }
        val debugPort = active.debugPort?.trim().orEmpty()
        if (debugPort.isBlank()) {
            return CrownBetPlacementResult(false, false, null, "browser_debug_port_missing")
        }
        val target = readCrownPageTarget(debugPort, command.loginUrl)
            ?: return CrownBetPlacementResult(false, false, null, "crown_page_not_found")
        val wsUrl = target.webSocketDebuggerUrl?.trim().orEmpty()
        if (wsUrl.isBlank()) {
            return CrownBetPlacementResult(false, false, null, "crown_debug_target_missing")
        }
        val snapshot = readPageSnapshotViaCdp(wsUrl, target)
            ?: return CrownBetPlacementResult(false, false, null, "crown_page_not_found")
        val session = CrownSessionPageAnalyzer.analyze(snapshot.text, snapshot.title)
        if (!session.loggedIn) {
            return CrownBetPlacementResult(false, false, null, session.accountStatus)
        }

        val argsJson = objectMapper.writeValueAsString(command)
        val resultText = evaluateCrownPageJson(wsUrl, crownBetExecutionScript(argsJson))
            ?: return CrownBetPlacementResult(false, false, null, "crown_execution_timeout")
        val result = runCatching { objectMapper.readTree(resultText) }.getOrNull()
            ?: return CrownBetPlacementResult(false, false, null, "crown_execution_parse_failed")
        return CrownBetPlacementResult(
            placed = result.path("placed").asBoolean(false),
            historyVerified = result.path("historyVerified").asBoolean(false),
            ticketReference = result.path("ticketReference").textOrNull(),
            message = result.path("message").textOrNull() ?: "crown_bet_failed",
            currentOdds = result.path("currentOdds").takeIf { !it.isMissingNode && !it.isNull }?.decimalValue()
        )
    }

    private fun readCrownPageSnapshot(debugPort: String, loginUrl: String?): CrownPageSnapshot? {
        val target = readCrownPageTarget(debugPort, loginUrl) ?: return null
        val wsUrl = target.webSocketDebuggerUrl?.trim().orEmpty()
        if (wsUrl.isBlank()) {
            return CrownPageSnapshot(target.pageUrl.orEmpty(), target.title.orEmpty(), "")
        }
        return readPageSnapshotViaCdp(wsUrl, target)
    }

    private fun readCrownPageSnapshots(debugPort: String, loginUrl: String?): List<CrownPageSnapshot> {
        return readCrownPageTargets(debugPort, loginUrl).mapNotNull { target ->
            val wsUrl = target.webSocketDebuggerUrl?.trim().orEmpty()
            if (wsUrl.isBlank()) {
                CrownPageSnapshot(target.pageUrl.orEmpty(), target.title.orEmpty(), "")
            } else {
                readPageSnapshotViaCdp(wsUrl, target)
            }
        }
    }

    private fun readCrownPageTarget(debugPort: String, loginUrl: String?): BrowserTarget? {
        return readCrownPageTargets(debugPort, loginUrl).firstOrNull()
    }

    private fun readCrownPageTargets(debugPort: String, loginUrl: String?): List<BrowserTarget> {
        val port = debugPort.toIntOrNull()?.takeIf { it in 1..65535 } ?: return emptyList()
        val endpoint = "http://127.0.0.1:$port/json/list".toHttpUrlOrNull() ?: return emptyList()
        val request = Request.Builder().url(endpoint).get().build()
        val targets = try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return emptyList()
                val root = objectMapper.readTree(response.body?.string().orEmpty()).takeIf { it.isArray } ?: return emptyList()
                root.map { node ->
                    BrowserTarget(
                        type = node.path("type").textOrNull(),
                        title = node.path("title").textOrNull(),
                        pageUrl = node.path("url").textOrNull(),
                        webSocketDebuggerUrl = node.path("webSocketDebuggerUrl").textOrNull()
                    )
                }
            }
        } catch (_: Exception) {
            return emptyList()
        }
        return selectCrownTargets(targets, loginUrl)
    }

    private fun selectCrownTarget(targets: List<BrowserTarget>, loginUrl: String?): BrowserTarget? {
        return selectCrownTargets(targets, loginUrl).firstOrNull()
    }

    private fun selectCrownTargets(targets: List<BrowserTarget>, loginUrl: String?): List<BrowserTarget> {
        val pageTargets = targets.filter { it.type == "page" && !it.pageUrl.isNullOrBlank() }
        val loginHost = hostFromUrl(loginUrl)
        val exactHostTargets = pageTargets.filter { it.pageUrl.hostEquals(loginHost) }
        val knownCrownTargets = pageTargets.filter { target ->
                val host = hostFromUrl(target.pageUrl).orEmpty()
                host.contains("mos077") || host.contains("hga") || host.contains("112.121.") || host == "134.159.80.63"
            }
        return (exactHostTargets + knownCrownTargets).distinctBy { target ->
            listOf(target.pageUrl.orEmpty(), target.webSocketDebuggerUrl.orEmpty(), target.title.orEmpty()).joinToString("|")
        }
    }

    private fun listLocalActiveProfiles(now: Long): List<AdsPowerActiveProfile> {
        val endpoint = buildUrl("/api/v1/browser/local-active") ?: return emptyList()
        val request = requestBuilder(endpoint).get().build()
        return try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return emptyList()
                val root = objectMapper.readTree(response.body?.string().orEmpty())
                if (root.path("code").asInt(-1) != 0) return emptyList()
                root.path("data").path("list").takeIf { it.isArray }?.mapNotNull { node ->
                    val profileId = node.path("user_id").textOrNull() ?: return@mapNotNull null
                    val debugPort = node.path("debug_port").textOrNull()
                        ?: parseDebugPort(node.path("ws").path("selenium").textOrNull())
                    AdsPowerActiveProfile(profileId, debugPort, now)
                }.orEmpty()
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun loadProfileMetadata(profileIds: List<String>): Map<String, AdsPowerProfileMetadata> {
        if (profileIds.isEmpty()) return emptyMap()
        val metadata = mutableMapOf<String, AdsPowerProfileMetadata>()
        profileIds.distinct().chunked(25).forEach { chunk ->
            chunk.forEach profileLoop@{ profileId ->
                val endpoint = buildUrl("/api/v1/user/list")
                    ?.newBuilder()
                    ?.addQueryParameter("user_id", profileId)
                    ?.addQueryParameter("page_size", "1")
                    ?.build() ?: return@profileLoop
                val request = requestBuilder(endpoint).get().build()
                val profile = try {
                    httpClient.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) return@use null
                        val root = objectMapper.readTree(response.body?.string().orEmpty())
                        if (root.path("code").asInt(-1) != 0) return@use null
                        val node = root.path("data").path("list").firstOrNull() ?: return@use null
                        AdsPowerProfileMetadata(
                            profileId = node.path("user_id").textOrNull() ?: profileId,
                            serialNumber = node.path("serial_number").textOrNull(),
                            name = node.path("name").textOrNull(),
                            username = node.path("username").textOrNull(),
                            remark = node.path("remark").textOrNull()
                        )
                    }
                } catch (_: Exception) {
                    null
                }
                if (profile != null) {
                    metadata[profile.profileId] = profile
                }
            }
        }
        return metadata
    }

    private fun loadProfileMetadataPage(): List<AdsPowerProfileMetadata> {
        val endpoint = buildUrl("/api/v1/user/list")
            ?.newBuilder()
            ?.addQueryParameter("page", "1")
            ?.addQueryParameter("page_size", "100")
            ?.build() ?: return emptyList()
        val request = requestBuilder(endpoint).get().build()
        return try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return emptyList()
                val root = objectMapper.readTree(response.body?.string().orEmpty())
                if (root.path("code").asInt(-1) != 0) return emptyList()
                root.path("data").path("list").takeIf { it.isArray }?.mapNotNull { node ->
                    val profileId = node.path("user_id").textOrNull() ?: return@mapNotNull null
                    AdsPowerProfileMetadata(
                        profileId = profileId,
                        serialNumber = node.path("serial_number").textOrNull(),
                        name = node.path("name").textOrNull(),
                        username = node.path("username").textOrNull(),
                        remark = node.path("remark").textOrNull()
                    )
                }.orEmpty()
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun buildCandidatesFromProfileMetadata(
        profile: AdsPowerProfileMetadata,
        loginUrl: String?,
        now: Long
    ): List<AdsPowerCrownSessionCandidateDto> {
        val activeByProfileId = checkProfileActive(profile.profileId, now)
        val active = if (activeByProfileId.opened) {
            activeByProfileId
        } else {
            profile.serialNumber?.let { checkProfileActive(it, now) }?.takeIf { it.opened }
        } ?: return emptyList()
        val snapshots = active.debugPort?.let { readCrownPageSnapshots(it, loginUrl) }.orEmpty()
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

    private fun parseDebugPort(value: String?): String? {
        val raw = value?.trim()?.takeIf { it.isNotBlank() } ?: return null
        raw.toIntOrNull()?.takeIf { it in 1..65535 }?.let { return it.toString() }
        runCatching { URI(raw).port }
            .getOrNull()
            ?.takeIf { it in 1..65535 }
            ?.let { return it.toString() }
        return raw.substringAfterLast(':')
            .substringBefore('/')
            .takeIf { it.toIntOrNull() in 1..65535 }
    }

    private fun readPageSnapshotViaCdp(wsUrl: String, target: BrowserTarget): CrownPageSnapshot? {
        if (!wsUrl.startsWith("ws://127.0.0.1:") && !wsUrl.startsWith("ws://localhost:")) {
            return null
        }
        val expression = """
            JSON.stringify({
              url: window.location.href,
              title: document.title,
              text: (() => {
                const seen = new Set();
                const collect = (win) => {
                  if (!win || seen.has(win)) return [];
                  seen.add(win);
                  const doc = win.document;
                  const parts = [doc.body ? doc.body.innerText : ''];
                  for (const frame of Array.from(win.frames || [])) {
                    try {
                      parts.push(...collect(frame));
                    } catch (_) {
                      // Ignore cross-origin frames; same-origin Crown frames still provide account text.
                    }
                  }
                  return parts;
                };
                try {
                  return collect(window).filter(Boolean).join('\n');
                } catch (_) {
                  return document.body ? document.body.innerText : '';
                }
              })()
            })
        """.trimIndent()
        val command = objectMapper.writeValueAsString(
            mapOf(
                "id" to 1,
                "method" to "Runtime.evaluate",
                "params" to mapOf(
                    "expression" to expression,
                    "returnByValue" to true,
                    "awaitPromise" to true
                )
            )
        )
        val rawJson = executeCdpCommand(wsUrl, command, timeoutSeconds = 5)
            ?: return CrownPageSnapshot(target.pageUrl.orEmpty(), target.title.orEmpty(), "")
        val snapshot = runCatching { objectMapper.readTree(rawJson) }.getOrNull()
            ?: return CrownPageSnapshot(target.pageUrl.orEmpty(), target.title.orEmpty(), rawJson)
        return CrownPageSnapshot(
            pageUrl = snapshot.path("url").textOrNull() ?: target.pageUrl.orEmpty(),
            title = snapshot.path("title").textOrNull() ?: target.title.orEmpty(),
            text = snapshot.path("text").textOrNull().orEmpty()
        )
    }

    private fun evaluateCrownPageJson(wsUrl: String, expression: String): String? {
        if (!wsUrl.startsWith("ws://127.0.0.1:") && !wsUrl.startsWith("ws://localhost:")) {
            return null
        }
        val command = objectMapper.writeValueAsString(
            mapOf(
                "id" to 1,
                "method" to "Runtime.evaluate",
                "params" to mapOf(
                    "expression" to expression,
                    "returnByValue" to true,
                    "awaitPromise" to true
                )
            )
        )
        return executeCdpCommand(wsUrl, command, timeoutSeconds = 35)
    }

    private fun executeCdpCommand(wsUrl: String, command: String, timeoutSeconds: Long): String? {
        val resultRef = AtomicReference<String?>()
        val latch = CountDownLatch(1)
        val request = Request.Builder().url(wsUrl).build()
        val webSocket = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(command)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val root = runCatching { objectMapper.readTree(text) }.getOrNull() ?: return
                if (root.path("id").asInt() != 1) return
                val value = root.path("result").path("result").path("value").textOrNull()
                resultRef.set(value)
                webSocket.close(1000, null)
                latch.countDown()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                latch.countDown()
            }
        })
        if (!latch.await(timeoutSeconds, TimeUnit.SECONDS)) {
            webSocket.cancel()
            return null
        }
        return resultRef.get()
    }

    private fun crownBetExecutionScript(argsJson: String): String {
        return """
            (async () => {
              const args = $argsJson;
              const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
              const finish = (payload) => JSON.stringify(payload);
              const normalizeLine = (value) => String(value || '')
                .replace(/\s+/g, '')
                .replace(/^\+/, '')
                .replace(/^-/, '');
              const currentText = () => document.body ? document.body.innerText : '';
              const currentRawText = () => document.body ? document.body.textContent : '';
              const readWagerCount = () => {
                const countText = [
                  document.getElementById('wager_count')?.innerText,
                  document.getElementById('pc_wager_count')?.innerText
                ].filter(Boolean).join(' ');
                const match = String(countText || '').match(/\d+/);
                return match ? Number(match[0]) : 0;
              };
              const extractReceiptReference = (text) => {
                const ticketMatch = String(text || '').match(/Ticket No:\s*([A-Za-z0-9-]+)/i);
                if (ticketMatch) return ticketMatch[1];
                const fantasyMatch = String(text || '').match(/\b(?:OU|HDP|FS|FT|BK)[A-Z0-9]*\d{6,}\b/i);
                return fantasyMatch ? fantasyMatch[0] : null;
              };
                const clickElement = (element) => {
                  element.scrollIntoView({ block: 'center', inline: 'center' });
                  for (const type of ['mouseover', 'mousedown', 'mouseup', 'click']) {
                    element.dispatchEvent(new MouseEvent(type, { bubbles: true, cancelable: true, view: window }));
                  }
                };
                const isVisible = (element) => Boolean(element && (
                  (() => {
                    const style = window.getComputedStyle(element);
                    return style.display !== 'none'
                      && style.visibility !== 'hidden'
                      && style.visibility !== 'collapse'
                      && style.opacity !== '0'
                      && (element.offsetWidth || element.offsetHeight || element.getClientRects().length);
                  })()
                ));
                const confirmBetIfPrompted = async () => {
                  const prompts = [
                    { container: 'alert_confirm', yes: 'yes_btn', checkbox: 'confirm_chk' },
                    { container: 'C_alert_confirm', yes: 'C_yes_btn', checkbox: 'C_confirm_chk' }
                  ];
                  for (let index = 0; index < 20; index += 1) {
                    for (const prompt of prompts) {
                      const container = document.getElementById(prompt.container);
                      if (!isVisible(container)) continue;
                      const checkbox = document.getElementById(prompt.checkbox);
                      if (checkbox && !checkbox.checked) {
                        checkbox.checked = true;
                        checkbox.dispatchEvent(new Event('change', { bubbles: true }));
                        await sleep(100);
                      }
                      const yesButton = document.getElementById(prompt.yes);
                      if (yesButton && isVisible(yesButton)) {
                        clickElement(yesButton);
                        await sleep(500);
                        return true;
                      }
                    }
                    await sleep(150);
                  }
                  return false;
                };
                const clearExistingSlip = async () => {
                  for (const button of Array.from(document.querySelectorAll('[id^="delete_betslip_"]'))) {
                    clickElement(button);
                    await sleep(250);
                  }
                  const closeButton = document.getElementById('order_close');
                  if (closeButton && isVisible(closeButton)) {
                    clickElement(closeButton);
                    await sleep(400);
                  }
                };
                const fillStakeInput = async (stakeInput, stake) => {
                  stakeInput.focus();
                  clickElement(stakeInput);
                  const keyboardVisible = () => isVisible(document.getElementById('num_0'))
                    && isVisible(document.getElementById('num_done'));
                  for (let index = 0; index < 8 && !keyboardVisible(); index += 1) {
                    await sleep(100);
                  }
                  if (keyboardVisible()) {
                    for (let index = 0; index < 14; index += 1) {
                      const deleteButton = document.getElementById('num_x');
                      if (deleteButton) clickElement(deleteButton);
                      await sleep(35);
                    }
                    for (const char of stake) {
                      const numberButton = document.getElementById('num_' + char);
                      if (!numberButton) return;
                      clickElement(numberButton);
                      await sleep(80);
                    }
                    const doneButton = document.getElementById('num_done');
                    if (doneButton) {
                      clickElement(doneButton);
                      await sleep(250);
                    }
                    return;
                  }
                  const valueSetter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')?.set;
                  if (valueSetter) {
                    valueSetter.call(stakeInput, '');
                    stakeInput.dispatchEvent(new Event('input', { bubbles: true }));
                  } else {
                    stakeInput.value = '';
                  }
                  stakeInput.dispatchEvent(new Event('input', { bubbles: true }));
                  for (const char of stake) {
                    stakeInput.dispatchEvent(new KeyboardEvent('keydown', {
                      bubbles: true,
                      cancelable: true,
                      key: char,
                      code: 'Digit' + char
                    }));
                    stakeInput.dispatchEvent(new KeyboardEvent('keypress', {
                      bubbles: true,
                      cancelable: true,
                      key: char,
                      code: 'Digit' + char
                    }));
                    const nextValue = String(stakeInput.value || '') + char;
                    if (valueSetter) {
                      valueSetter.call(stakeInput, nextValue);
                    } else {
                      stakeInput.value = nextValue;
                    }
                    stakeInput.dispatchEvent(new InputEvent('input', {
                      bubbles: true,
                      inputType: 'insertText',
                      data: char
                    }));
                    stakeInput.dispatchEvent(new KeyboardEvent('keyup', {
                      bubbles: true,
                      cancelable: true,
                      key: char,
                      code: 'Digit' + char
                    }));
                    await sleep(60);
                  }
                  stakeInput.dispatchEvent(new Event('change', { bubbles: true }));
                };
                const waitFor = async (reader, timeoutMs = 10000, intervalMs = 250) => {
                  const deadline = Date.now() + timeoutMs;
                  while (Date.now() <= deadline) {
                  const value = reader();
                  if (value) return value;
                  await sleep(intervalMs);
                }
                return null;
              };
              const openLiveSoccer = async () => {
                const existing = document.getElementById(args.betElementId);
                if (existing) return existing;
                const candidates = ['old_ft_live_league', 'ft_live_league', 'live_page'];
                for (const id of candidates) {
                  const element = document.getElementById(id);
                  if (element) {
                    clickElement(element);
                    await sleep(1200);
                    const found = document.getElementById(args.betElementId);
                    if (found) return found;
                  }
                  }
                  return waitFor(() => document.getElementById(args.betElementId), 8000, 300);
                };
                await clearExistingSlip();
                const betElement = await openLiveSoccer();
                if (!betElement) {
                  return finish({ placed: false, historyVerified: false, message: 'crown_market_not_found' });
              }
              if (betElement.classList.contains('lock')) {
                return finish({ placed: false, historyVerified: false, message: 'crown_market_locked' });
              }
              const lineText = Array.from(betElement.querySelectorAll('.text_ballhead'))
                .map((element) => element.innerText)
                .join(' ');
              if (args.lineValue && normalizeLine(lineText) !== normalizeLine(args.lineValue)) {
                return finish({
                  placed: false,
                  historyVerified: false,
                  message: 'crown_line_mismatch',
                  currentLine: lineText
                });
              }
              const oddsText = (betElement.querySelector('.text_odds')?.innerText || '').trim();
              const currentOdds = Number(oddsText);
                if (!Number.isFinite(currentOdds)) {
                  return finish({ placed: false, historyVerified: false, message: 'crown_odds_missing' });
                }
                const oddsDelta = Math.abs(Number((currentOdds - Number(args.targetOdds)).toFixed(4)));
                if (oddsDelta > Number(args.oddsTolerance)) {
                  return finish({
                    placed: false,
                    historyVerified: false,
                  message: 'crown_odds_moved',
                  currentOdds
                });
              }
  
                const beforeWagerCount = readWagerCount();
                clickElement(betElement);
                const stake = String(Number(args.stakeAmount));
                const stakeInput = await waitFor(() => document.querySelector('input#bet_gold_pc')
                  || document.querySelector('input#bet_gold')
                  || document.querySelector('input[placeholder="Enter Stake"]'), 10000, 250);
                if (!stakeInput) {
                  return finish({ placed: false, historyVerified: false, message: 'crown_stake_input_missing', currentOdds });
                }
                await fillStakeInput(stakeInput, stake);
  
                let orderButton = await waitFor(() => {
                  const button = document.getElementById('order_bet');
                  return button && !button.disabled ? button : null;
                }, 4000, 250);
                if (!orderButton) {
                  const addButton = document.getElementById('add_total_bet');
                  if (!addButton || addButton.disabled || !isVisible(addButton)) {
                    const pageText = currentText();
                    if (/minimum stake|min(?:imum)?\\.? stake/i.test(pageText)) {
                      return finish({ placed: false, historyVerified: false, message: 'crown_stake_below_minimum', currentOdds });
                    }
                    return finish({ placed: false, historyVerified: false, message: 'crown_place_button_disabled', currentOdds });
                  }
                  clickElement(addButton);
                  const betElementParts = String(args.betElementId || '').split('_');
                  const ecid = betElementParts.length >= 3 ? betElementParts[2] : '';
                  const slipStakeInput = await waitFor(() => {
                    const byEcid = ecid ? document.querySelector('input#bet_gold_' + ecid + '_pc') : null;
                    if (byEcid && isVisible(byEcid)) return byEcid;
                    return Array.from(document.querySelectorAll('input[id^="bet_gold_"][id$="_pc"]'))
                      .find((input) => isVisible(input));
                  }, 8000, 250);
                  if (!slipStakeInput) {
                    return finish({ placed: false, historyVerified: false, message: 'crown_betslip_stake_input_missing', currentOdds });
                  }
                  await fillStakeInput(slipStakeInput, stake);
                  orderButton = await waitFor(() => {
                    const button = document.getElementById('order_bet');
                    return button && !button.disabled ? button : null;
                  }, 8000, 250);
                }
                if (!orderButton) {
                  const pageText = currentText();
                  if (/minimum stake|min(?:imum)?\\.? stake/i.test(pageText)) {
                    return finish({ placed: false, historyVerified: false, message: 'crown_stake_below_minimum', currentOdds });
                  }
                  if (/maximum stake|max(?:imum)?\\.? stake/i.test(pageText)) {
                    return finish({ placed: false, historyVerified: false, message: 'crown_stake_above_maximum', currentOdds });
                  }
                  return finish({ placed: false, historyVerified: false, message: 'crown_place_button_disabled', currentOdds });
                }
              clickElement(orderButton);
              await confirmBetIfPrompted();

              const receiptText = await waitFor(() => {
                const confirmVisible = isVisible(document.getElementById('alert_confirm'))
                  || isVisible(document.getElementById('C_alert_confirm'));
                if (confirmVisible) {
                  confirmBetIfPrompted();
                }
                const text = currentText();
                if (/successfully placed|BET RECEIPT|Ticket No:/i.test(text)) return text;
                if (/Rejected|Failed to Place|not placed|incorrect|maximum|minimum/i.test(text)) return text;
                return null;
              }, 18000, 500);
              if (!receiptText || !/successfully placed|BET RECEIPT|Ticket No:/i.test(receiptText)) {
                return finish({
                  placed: false,
                  historyVerified: false,
                  message: 'crown_bet_not_confirmed',
                  currentOdds,
                  pageText: String(receiptText || currentText()).slice(0, 500)
                });
              }
              const ticketReference = extractReceiptReference(receiptText);

              const historyButton = document.getElementById('header_todaywagers')
                || document.getElementById('menu_todaywagers');
              if (historyButton) {
                clickElement(historyButton);
                await sleep(1800);
              }
              const historyText = await waitFor(() => {
                const text = currentText();
                const rawText = currentRawText();
                const currentWagerCount = readWagerCount();
                if (ticketReference && (text.includes(ticketReference) || rawText.includes(ticketReference))) {
                  return rawText || text;
                }
                if (currentWagerCount > beforeWagerCount && /successfully placed|BET RECEIPT/i.test(rawText || text)) {
                  return rawText || text;
                }
                if (/Confirmed|Open Bets|OPEN BETS|My Bets/i.test(text)
                  && text.includes(stake)
                  && (text.includes('Al ') || /HDP|O\/U/i.test(text))) {
                  return text;
                }
                return null;
              }, 12000, 500);
              if (!historyText) {
                return finish({
                  placed: true,
                  historyVerified: false,
                  ticketReference,
                  message: 'crown_history_unverified',
                  currentOdds
                });
              }
              return finish({
                placed: true,
                historyVerified: true,
                ticketReference,
                message: 'crown_history_verified',
                currentOdds
              });
            })()
        """.trimIndent()
    }

    private fun buildUrl(path: String): okhttp3.HttpUrl? {
        val base = normalizedBaseUrl().toHttpUrlOrNull() ?: return null
        if (!isLocalHost(base.host)) return null
        return "${normalizedBaseUrl()}$path".toHttpUrlOrNull()
    }

    private fun requestBuilder(url: okhttp3.HttpUrl): Request.Builder {
        val builder = Request.Builder().url(url)
        val token = apiKey?.trim().orEmpty()
        if (token.isNotBlank()) {
            builder.header("Authorization", "Bearer $token")
        }
        return builder
    }

    private fun normalizedBaseUrl() = baseUrl.trim().trimEnd('/')

    private fun isLocalHost(host: String): Boolean {
        val normalized = host.trim().lowercase().removeSurrounding("[", "]")
        return normalized == "127.0.0.1" || normalized == "localhost" || normalized == "::1"
    }

    private fun com.fasterxml.jackson.databind.JsonNode.textOrNull(): String? {
        return takeIf { !it.isMissingNode && !it.isNull }?.asText()?.takeIf { it.isNotBlank() }
    }

    private fun com.fasterxml.jackson.databind.JsonNode?.debugPortOrNull(): String? {
        val data = this ?: return null
        return data.path("debug_port").textOrNull()
            ?: parseDebugPort(data.path("ws").path("selenium").textOrNull())
            ?: parseDebugPort(data.path("ws").path("puppeteer").textOrNull())
    }

    private fun String.isAdsPowerSerialNumber(): Boolean {
        return isNotBlank() && all { it.isDigit() }
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

    private fun hostFromUrl(url: String?): String? {
        val raw = url?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return runCatching { URI(raw).host }
            .getOrNull()
            ?.trim()
            ?.lowercase()
            ?.removePrefix("www.")
    }

    private fun String?.hostEquals(host: String?): Boolean {
        val normalized = hostFromUrl(this)
        return !normalized.isNullOrBlank() && !host.isNullOrBlank() && normalized == host
    }

    private data class BrowserTarget(
        val type: String?,
        val title: String?,
        val pageUrl: String?,
        val webSocketDebuggerUrl: String?
    )

    private data class CrownPageSnapshot(
        val pageUrl: String,
        val title: String,
        val text: String
    )

    private data class CrownAnalyzedSnapshot(
        val snapshot: CrownPageSnapshot,
        val analysis: CrownSessionAnalysis
    )

    private data class AdsPowerActiveProfile(
        val profileId: String,
        val debugPort: String?,
        val checkedAt: Long
    )

    private data class AdsPowerProfileMetadata(
        val profileId: String,
        val serialNumber: String?,
        val name: String?,
        val username: String?,
        val remark: String?
    )

    companion object {
        private const val DEFAULT_BASE_URL = "http://127.0.0.1:50325"
    }
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
        val normalized = text.replace('\u00A0', ' ').trim()
        if (normalized.isBlank()) {
            return CrownSessionAnalysis(false, "unknown", null, message = "皇冠页面未返回内容")
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
