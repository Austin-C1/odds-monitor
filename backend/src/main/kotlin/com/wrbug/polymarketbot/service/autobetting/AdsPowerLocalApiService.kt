package com.wrbug.polymarketbot.service.autobetting

import com.fasterxml.jackson.databind.ObjectMapper
import com.wrbug.polymarketbot.dto.AdsPowerBrowserSessionDto
import com.wrbug.polymarketbot.dto.AdsPowerCrownSessionCandidateDto
import com.wrbug.polymarketbot.dto.AdsPowerCrownSessionDto
import com.wrbug.polymarketbot.dto.AdsPowerProfileActiveDto
import com.wrbug.polymarketbot.dto.AdsPowerStatusDto
import com.wrbug.polymarketbot.util.TextEncodingUtils
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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

private const val CROWN_NETWORK_UNSTABLE_STATUS = "crown_network_unstable"
private const val CROWN_BET_PLACEMENT_TIMEOUT_SECONDS = 45L
private val nativePlaceBetClickReasons = setOf(
    "crown_place_button_native_click_required",
    "crown_place_button_click_failed",
    "crown_bet_not_confirmed"
)

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

    override fun placeBet(command: CrownBetPlacementCommand): CrownBetPlacementResult {
        val active = checkProfileActive(command.profileId)
        if (!active.opened) {
            return CrownBetPlacementResult(false, false, null, "profile_not_opened")
        }
        val debugPort = active.debugPort?.trim().orEmpty()
        if (debugPort.isBlank()) {
            return CrownBetPlacementResult(false, false, null, "browser_debug_port_missing")
        }
        closeCrownPrintTargets(debugPort)
        val target = readCrownPageTarget(debugPort, command.loginUrl)
            ?: return CrownBetPlacementResult(false, false, null, "crown_page_not_found")
        val initialWsUrl = target.webSocketDebuggerUrl?.trim().orEmpty()
        if (initialWsUrl.isBlank()) {
            return CrownBetPlacementResult(false, false, null, "crown_debug_target_missing")
        }
        installCrownPrintGuard(initialWsUrl)
        val activated = activateCrownPageBeforePlacement(debugPort, target, command.loginUrl)
            ?: return CrownBetPlacementResult(false, false, null, "crown_page_activation_failed")
        val wsUrl = activated.wsUrl
        installCrownPrintGuard(wsUrl)
        val snapshot = activated.snapshot
        val session = CrownSessionPageAnalyzer.analyze(snapshot.text, snapshot.title)
        if (!session.loggedIn) {
            if (session.accountStatus == CROWN_NETWORK_UNSTABLE_STATUS) {
                dismissCrownNetworkPrompt(wsUrl)
            }
            return CrownBetPlacementResult(false, false, null, session.accountStatus)
        }

        val argsJson = objectMapper.writeValueAsString(command)
        val resultText = try {
            evaluateCrownPageJson(wsUrl, crownBetExecutionScript(argsJson))
        } finally {
            closeCrownPrintTargets(debugPort)
        } ?: return CrownBetPlacementResult(false, false, null, "crown_execution_timeout")
        val result = runCatching { objectMapper.readTree(resultText) }.getOrNull()
            ?: return CrownBetPlacementResult(false, false, null, "crown_execution_parse_failed")
        val placementResult = result.toCrownBetPlacementResult()
        if (shouldDispatchNativePlaceBetClick(placementResult.message, result) && dispatchNativePlaceBetClick(wsUrl, result)) {
            val nativeResultText = evaluateCrownPageJson(
                wsUrl,
                crownBetHistoryVerificationScript(argsJson, objectMapper.writeValueAsString(""))
            )
                ?: return placementResult
            val nativeResult = runCatching { objectMapper.readTree(nativeResultText) }.getOrNull()
                ?: return placementResult
            dispatchNativeReceiptOkClick(wsUrl, nativeResult)
            if (nativeResult.path("historyVerified").asBoolean(false)) {
                return nativeResult.toCrownBetPlacementResult()
            }
        }
        dispatchNativeReceiptOkClick(wsUrl, result)
        return placementResult
    }

    override fun verifyPlacedBet(command: CrownBetPlacementCommand, ticketReference: String?): CrownBetPlacementResult {
        val active = checkProfileActive(command.profileId)
        if (!active.opened) {
            return CrownBetPlacementResult(true, false, ticketReference, "profile_not_opened")
        }
        val debugPort = active.debugPort?.trim().orEmpty()
        if (debugPort.isBlank()) {
            return CrownBetPlacementResult(true, false, ticketReference, "browser_debug_port_missing")
        }
        closeCrownPrintTargets(debugPort)
        val target = readCrownPageTarget(debugPort, command.loginUrl)
            ?: return CrownBetPlacementResult(true, false, ticketReference, "crown_page_not_found")
        val initialWsUrl = target.webSocketDebuggerUrl?.trim().orEmpty()
        if (initialWsUrl.isBlank()) {
            return CrownBetPlacementResult(true, false, ticketReference, "crown_debug_target_missing")
        }
        installCrownPrintGuard(initialWsUrl)
        val activated = activateCrownPageBeforePlacement(debugPort, target, command.loginUrl)
            ?: return CrownBetPlacementResult(true, false, ticketReference, "crown_page_activation_failed")
        val wsUrl = activated.wsUrl
        installCrownPrintGuard(wsUrl)
        val session = CrownSessionPageAnalyzer.analyze(activated.snapshot.text, activated.snapshot.title)
        if (!session.loggedIn) {
            if (session.accountStatus == CROWN_NETWORK_UNSTABLE_STATUS) {
                dismissCrownNetworkPrompt(wsUrl)
            }
            return CrownBetPlacementResult(true, false, ticketReference, session.accountStatus)
        }

        val argsJson = objectMapper.writeValueAsString(command)
        val ticketReferenceJson = objectMapper.writeValueAsString(ticketReference.orEmpty())
        val resultText = evaluateCrownPageJson(wsUrl, crownBetHistoryVerificationScript(argsJson, ticketReferenceJson))
            ?: return CrownBetPlacementResult(true, false, ticketReference, "crown_history_unverified")
        val result = runCatching { objectMapper.readTree(resultText) }.getOrNull()
            ?: return CrownBetPlacementResult(true, false, ticketReference, "crown_execution_parse_failed")
        dispatchNativeReceiptOkClick(wsUrl, result)
        return CrownBetPlacementResult(
            placed = true,
            historyVerified = result.path("historyVerified").asBoolean(false),
            ticketReference = result.path("ticketReference").textOrNull() ?: ticketReference,
            message = result.path("message").textOrNull() ?: "crown_history_unverified"
        )
    }

    private fun activateCrownPageBeforePlacement(
        debugPort: String,
        target: BrowserTarget,
        loginUrl: String?
    ): CrownPageActivationResult? {
        val initialWsUrl = target.webSocketDebuggerUrl?.trim().orEmpty()
        if (initialWsUrl.isBlank()) return null

        val activeTarget = readCrownPageTarget(debugPort, loginUrl) ?: target
        val activeWsUrl = activeTarget.webSocketDebuggerUrl?.trim()?.takeIf { it.isNotBlank() } ?: initialWsUrl
        var snapshot = waitForCrownPageSnapshot(activeWsUrl, activeTarget) ?: return null
        if (CrownSessionPageAnalyzer.analyze(snapshot.text, snapshot.title).accountStatus == CROWN_NETWORK_UNSTABLE_STATUS) {
            dismissCrownNetworkPrompt(activeWsUrl)
            snapshot = waitForCrownPageSnapshot(activeWsUrl, activeTarget) ?: snapshot
        }
        return CrownPageActivationResult(activeTarget, activeWsUrl, snapshot)
    }

    private fun waitForCrownPageSnapshot(wsUrl: String, target: BrowserTarget): CrownPageSnapshot? {
        var lastSnapshot: CrownPageSnapshot? = null
        repeat(6) {
            val snapshot = readPageSnapshotViaCdp(wsUrl, target)
            if (snapshot != null) {
                lastSnapshot = snapshot
                if (snapshot.text.isNotBlank()) return snapshot
            }
            Thread.sleep(750)
        }
        return lastSnapshot
    }

    private fun dismissCrownNetworkPrompt(wsUrl: String): Boolean {
        val expression = """
            (async () => {
              const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
              const networkPattern = /网络不稳定|網絡不穩定|网络异常|網絡異常|重新更新|重新整理|network.{0,24}(unstable|error)|please.{0,24}(refresh|reload)/i;
              const seen = new Set();
              const documents = [];
              const visit = (win) => {
                if (!win || seen.has(win)) return;
                seen.add(win);
                try {
                  if (win.document) documents.push(win.document);
                  for (const frame of Array.from(win.frames || [])) visit(frame);
                } catch (_) {
                  // Ignore cross-origin frames.
                }
              };
              visit(window);
              const isVisible = (element) => Boolean(element && (() => {
                const view = element.ownerDocument?.defaultView || window;
                const style = view.getComputedStyle(element);
                return style.display !== 'none'
                  && style.visibility !== 'hidden'
                  && style.visibility !== 'collapse'
                  && style.opacity !== '0'
                  && (element.offsetWidth || element.offsetHeight || element.getClientRects().length);
              })());
              const fireClick = (element) => {
                const view = element.ownerDocument?.defaultView || window;
                const MouseEventCtor = view.MouseEvent || window.MouseEvent;
                element.scrollIntoView({ block: 'center', inline: 'center' });
                for (const type of ['mouseover', 'mousedown', 'mouseup', 'click']) {
                  element.dispatchEvent(new MouseEventCtor(type, { bubbles: true, cancelable: true, view }));
                }
              };
              const pageText = documents
                .map((doc) => doc.body ? doc.body.innerText || doc.body.textContent || '' : '')
                .join('\n');
              if (!networkPattern.test(pageText)) {
                return JSON.stringify({ networkPrompt: false, dismissed: false });
              }
              const buttons = documents.flatMap((doc) => Array.from(doc.querySelectorAll('button, input[type="button"], input[type="submit"], [role="button"], a')));
              const button = buttons.find((element) => {
                const text = String(element.innerText || element.value || element.textContent || '').trim();
                return isVisible(element) && /确认|確定|确定|OK|知道|关闭|Close/i.test(text);
              }) || buttons.find(isVisible);
              if (button) {
                fireClick(button);
                await sleep(300);
              }
              return JSON.stringify({ networkPrompt: true, dismissed: Boolean(button) });
            })()
        """.trimIndent()
        val result = evaluateCrownPageJson(wsUrl, expression) ?: return false
        return runCatching { objectMapper.readTree(result).path("networkPrompt").asBoolean(false) }.getOrDefault(false)
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
        closeCrownPrintTargets(debugPort)
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
        return selectCrownTargets(readBrowserTargets(debugPort), loginUrl)
    }

    private fun readBrowserTargets(debugPort: String): List<BrowserTarget> {
        val port = debugPort.toIntOrNull()?.takeIf { it in 1..65535 } ?: return emptyList()
        val endpoint = "http://127.0.0.1:$port/json/list".toHttpUrlOrNull() ?: return emptyList()
        val request = Request.Builder().url(endpoint).get().build()
        return try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return emptyList()
                val root = objectMapper.readTree(response.body?.string().orEmpty()).takeIf { it.isArray } ?: return emptyList()
                root.map { node ->
                    BrowserTarget(
                        id = node.path("id").textOrNull(),
                        type = node.path("type").textOrNull(),
                        title = node.path("title").textOrNull(),
                        pageUrl = node.path("url").textOrNull(),
                        webSocketDebuggerUrl = node.path("webSocketDebuggerUrl").textOrNull()
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun crownPrintGuardScript(): String {
        return """
            (() => {
              const install = (win) => {
                if (!win || win.__autoBetPrintGuardInstalled) return;
                win.__autoBetPrintGuardInstalled = true;
                const noop = () => {};
                const fakeBody = { innerHTML: '', innerText: '', textContent: '', appendChild: noop };
                const fakeDocument = {
                  body: fakeBody,
                  documentElement: fakeBody,
                  write: noop,
                  writeln: noop,
                  close: noop,
                  open: () => fakeDocument,
                  createElement: () => fakeBody
                };
                const fakePopup = {
                  closed: false,
                  document: fakeDocument,
                  location: { href: 'about:blank' },
                  print: noop,
                  close: noop,
                  focus: noop,
                  blur: noop,
                  addEventListener: noop,
                  removeEventListener: noop
                };
                try {
                  Object.defineProperty(win, 'print', {
                    configurable: true,
                    writable: true,
                    value: noop
                  });
                } catch (_) {
                  try { win.print = noop; } catch (_) {}
                }
                try {
                  if (!win.__autoBetOriginalOpen && typeof win.open === 'function') {
                    win.__autoBetOriginalOpen = win.open.bind(win);
                  }
                  const openWindow = win.__autoBetOriginalOpen;
                  if (typeof openWindow === 'function') {
                    Object.defineProperty(win, 'open', {
                      configurable: true,
                      writable: true,
                      value: (...openArgs) => {
                        const targetUrl = String(openArgs[0] || '');
                        const targetName = String(openArgs[1] || '');
                        if (!targetUrl || /print|receipt|statement|wager|welcome/i.test(targetUrl) || /print|receipt|statement|wager|welcome/i.test(targetName)) {
                          return fakePopup;
                        }
                        const popup = openWindow(...openArgs);
                        try {
                          if (popup) {
                            install(popup);
                            setTimeout(() => install(popup), 50);
                            setTimeout(() => install(popup), 250);
                          }
                        } catch (_) {
                          // Ignore popup access failures.
                        }
                        return popup;
                      }
                    });
                  }
                } catch (_) {
                  // Ignore window.open guard failures.
                }
                try {
                  if (win.document && !win.document.__autoBetOriginalExecCommand && typeof win.document.execCommand === 'function') {
                    win.document.__autoBetOriginalExecCommand = win.document.execCommand.bind(win.document);
                  }
                  const execCommand = win.document?.__autoBetOriginalExecCommand;
                  if (typeof execCommand === 'function') {
                    win.document.execCommand = (...execArgs) => {
                      const command = String(execArgs[0] || '').toLowerCase();
                      if (command === 'print') return false;
                      return execCommand(...execArgs);
                    };
                  }
                } catch (_) {
                  // Ignore document command guard failures.
                }
                try {
                  win.onbeforeprint = null;
                  win.onafterprint = null;
                  if (win.document) {
                    win.document.onbeforeprint = null;
                    win.document.onafterprint = null;
                  }
                } catch (_) {
                  // Ignore print handler cleanup failures.
                }
              };
              const visit = (win) => {
                try {
                  install(win);
                  for (const frame of Array.from(win.frames || [])) {
                    visit(frame);
                  }
                } catch (_) {
                  // Ignore cross-frame print guard failures.
                }
              };
              visit(window);
              if (!window.__autoBetPrintGuardTimer) {
                window.__autoBetPrintGuardTimer = setInterval(() => visit(window), 250);
              }
              return true;
            })()
        """.trimIndent()
    }

    private fun installCrownPrintGuard(wsUrl: String) {
        if (!wsUrl.startsWith("ws://127.0.0.1:") && !wsUrl.startsWith("ws://localhost:")) {
            return
        }
        val source = crownPrintGuardScript()
        val addScriptCommand = objectMapper.writeValueAsString(
            mapOf(
                "id" to 1,
                "method" to "Page.addScriptToEvaluateOnNewDocument",
                "params" to mapOf("source" to source)
            )
        )
        executeCdpCommand(wsUrl, addScriptCommand, timeoutSeconds = 3)
        val evaluateCommand = objectMapper.writeValueAsString(
            mapOf(
                "id" to 1,
                "method" to "Runtime.evaluate",
                "params" to mapOf(
                    "expression" to source,
                    "returnByValue" to true,
                    "awaitPromise" to true
                )
            )
        )
        executeCdpCommand(wsUrl, evaluateCommand, timeoutSeconds = 3)
    }

    private fun closeCrownPrintTargets(debugPort: String) {
        val port = debugPort.toIntOrNull()?.takeIf { it in 1..65535 } ?: return
        val printTargets = readBrowserTargets(debugPort).filter { target ->
            val text = listOf(target.title, target.pageUrl).filterNotNull().joinToString(" ")
            val printLike = Regex("""chrome://print|print|打印|列印""", RegexOption.IGNORE_CASE).containsMatchIn(text)
            target.type == "page" && !target.id.isNullOrBlank() && printLike
        }
        for (target in printTargets) {
            val endpoint = "http://127.0.0.1:$port/json/close/${target.id}".toHttpUrlOrNull() ?: continue
            val request = Request.Builder().url(endpoint).get().build()
            runCatching {
                httpClient.newCall(request).execute().close()
            }
        }
    }

    private fun selectCrownTarget(targets: List<BrowserTarget>, loginUrl: String?): BrowserTarget? {
        return selectCrownTargets(targets, loginUrl).firstOrNull()
    }

    private fun selectCrownTargets(targets: List<BrowserTarget>, loginUrl: String?): List<BrowserTarget> {
        val pageTargets = targets.filter { it.type == "page" && it.pageUrl.isUsableCrownPageCandidate() }
        val loginHost = hostFromUrl(loginUrl)
        val exactHostTargets = pageTargets.filter { it.pageUrl.hostEquals(loginHost) }
        val fallbackTargets = if (exactHostTargets.isEmpty()) pageTargets else emptyList()
        return (exactHostTargets + fallbackTargets)
            .distinctBy { target ->
                listOf(target.pageUrl.orEmpty(), target.webSocketDebuggerUrl.orEmpty(), target.title.orEmpty()).joinToString("|")
            }
            .sortedBy(::crownTargetPriority)
    }

    private fun crownTargetPriority(target: BrowserTarget): Int {
        val text = listOf(target.title, target.pageUrl)
            .filterNotNull()
            .joinToString(" ")
            .lowercase()
        return when {
            Regex("""betslip|bet[_-]?slip|wager|order|statement|ticket|receipt|注单|投注记录""").containsMatchIn(text) -> 20
            Regex("""football|soccer|league|today|early|live|in-play|滚球|足球|联赛""").containsMatchIn(text) -> 0
            else -> 10
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
                  const readNodeText = (selector) => {
                    const node = doc.querySelector(selector);
                    return node ? (node.innerText || node.textContent || '').trim() : '';
                  };
                  const signalLines = [];
                  const pushSignal = (label, value) => {
                    const text = String(value || '').replace(/\s+/g, ' ').trim();
                    if (text) signalLines.push(`${'$'}{label}: ${'$'}{text}`);
                  };
                  pushSignal('username', readNodeText('#acc_username'));
                  pushSignal('balance', readNodeText('#header_money') || readNodeText('.money_header') || readNodeText('#acc_money'));
                  const credit = readNodeText('#header_credit');
                  const currency = readNodeText('#header_currency') || 'RMB';
                  if (credit) pushSignal('balance', `${'$'}{currency} ${'$'}{credit}`);
                  try {
                    if (win.userData && typeof win.userData === 'object') {
                      pushSignal('username', win.userData.username);
                    }
                  } catch (_) {
                    // Ignore locked global values from the betting page.
                  }
                  parts.push(...signalLines);
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
        return executeCdpCommand(wsUrl, command, timeoutSeconds = CROWN_BET_PLACEMENT_TIMEOUT_SECONDS)
    }

    private fun dispatchNativePlaceBetClick(
        wsUrl: String,
        result: com.fasterxml.jackson.databind.JsonNode
    ): Boolean = dispatchNativeJsonPointClick(wsUrl, result, "placeButton")

    private fun dispatchNativeReceiptOkClick(
        wsUrl: String,
        result: com.fasterxml.jackson.databind.JsonNode
    ): Boolean = dispatchNativeJsonPointClick(wsUrl, result, "receiptOkButton")

    private fun dispatchNativeJsonPointClick(
        wsUrl: String,
        result: com.fasterxml.jackson.databind.JsonNode,
        fieldName: String
    ): Boolean {
        val x = result.path(fieldName).path("x").asDouble(Double.NaN)
        val y = result.path(fieldName).path("y").asDouble(Double.NaN)
        if (!x.isFinite() || !y.isFinite() || x <= 0.0 || y <= 0.0) return false
        return dispatchCdpMouseClick(wsUrl, x, y)
    }

    private fun shouldDispatchNativePlaceBetClick(
        message: String,
        result: com.fasterxml.jackson.databind.JsonNode
    ): Boolean {
        if (message !in nativePlaceBetClickReasons) return false
        val point = result.path("placeButton")
        val x = point.path("x").asDouble(Double.NaN)
        val y = point.path("y").asDouble(Double.NaN)
        return x.isFinite() && y.isFinite() && x > 0.0 && y > 0.0
    }

    private fun dispatchCdpMouseClick(wsUrl: String, x: Double, y: Double): Boolean {
        if (!wsUrl.startsWith("ws://127.0.0.1:") && !wsUrl.startsWith("ws://localhost:")) {
            return false
        }
        val acknowledged = AtomicBoolean(false)
        val latch = CountDownLatch(1)
        val request = Request.Builder().url(wsUrl).build()
        val commands = listOf(
            "mouseMoved" to 0,
            "mousePressed" to 1,
            "mouseReleased" to 0
        ).mapIndexed { index, (type, buttons) ->
            objectMapper.writeValueAsString(
                mapOf(
                    "id" to index + 1,
                    "method" to "Input.dispatchMouseEvent",
                    "params" to mapOf(
                        "type" to type,
                        "x" to x,
                        "y" to y,
                        "button" to "left",
                        "buttons" to buttons,
                        "clickCount" to if (type == "mouseMoved") 0 else 1
                    )
                )
            )
        }
        val webSocket = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                commands.forEach(webSocket::send)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val root = runCatching { objectMapper.readTree(text) }.getOrNull() ?: return
                if (root.path("id").asInt() != commands.size) return
                acknowledged.set(!root.has("error"))
                webSocket.close(1000, null)
                latch.countDown()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                latch.countDown()
            }
        })
        return try {
            latch.await(5, TimeUnit.SECONDS) && acknowledged.get()
        } finally {
            webSocket.cancel()
        }
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
        return try {
            if (!latch.await(timeoutSeconds, TimeUnit.SECONDS)) {
                null
            } else {
                resultRef.get()
            }
        } finally {
            webSocket.cancel()
        }
    }

    private fun crownBetExecutionScript(argsJson: String): String {
        return """
            (async () => {
              const args = $argsJson;
              const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
              const finish = (payload) => JSON.stringify(payload);
              const accessibleWindows = () => {
                const windows = [];
                const seen = new Set();
                const visit = (win) => {
                  if (!win || seen.has(win)) return;
                  seen.add(win);
                  let doc = null;
                  try {
                    doc = win.document;
                  } catch (_) {
                    return;
                  }
                  if (!doc) return;
                  windows.push(win);
                  for (const frame of Array.from(win.frames || [])) {
                    try {
                      visit(frame);
                    } catch (_) {
                      // Ignore cross-origin frames.
                    }
                  }
                };
                visit(window);
                return windows;
              };
              const findInDocuments = (reader) => {
                for (const win of accessibleWindows()) {
                  try {
                    const value = reader(win.document, win);
                    if (value) return value;
                  } catch (_) {
                    // Ignore transient frame access errors.
                  }
                }
                return null;
              };
              const allInDocuments = (reader) => {
                const values = [];
                for (const win of accessibleWindows()) {
                  try {
                    values.push(...reader(win.document, win).filter(Boolean));
                  } catch (_) {
                    // Ignore transient frame access errors.
                  }
                }
                return values;
              };
              const findElementById = (id) => findInDocuments((doc) => doc.getElementById(id));
              const findSelector = (selector) => findInDocuments((doc) => doc.querySelector(selector));
              const findAllSelector = (selector) => allInDocuments((doc) => Array.from(doc.querySelectorAll(selector)));
              const ownerWindow = (element) => element?.ownerDocument?.defaultView || window;
              const normalizeLine = (value) => String(value || '')
                .replace(/\s+/g, '')
                .replace(/^\+/, '')
                .replace(/^-/, '');
              const currentText = () => allInDocuments((doc) => [doc.body ? doc.body.innerText : ''])
                .filter(Boolean)
                .join('\n');
              const currentRawText = () => allInDocuments((doc) => [doc.body ? doc.body.textContent : ''])
                .filter(Boolean)
                .join('\n');
              const disableNativePrint = () => {
                const guardWindow = (win) => {
                  const noop = () => {};
                  const fakeBody = { innerHTML: '', innerText: '', textContent: '', appendChild: noop };
                  const fakeDocument = {
                    body: fakeBody,
                    documentElement: fakeBody,
                    write: noop,
                    writeln: noop,
                    close: noop,
                    open: () => fakeDocument,
                    createElement: () => fakeBody
                  };
                  const fakePopup = {
                    print: noop,
                    close: noop,
                    focus: noop,
                    blur: noop,
                    closed: false,
                    document: fakeDocument,
                    location: { href: 'about:blank' },
                    addEventListener: noop,
                    removeEventListener: noop
                  };
                  try {
                    Object.defineProperty(win, 'print', {
                      configurable: true,
                      writable: true,
                      value: noop
                    });
                  } catch (_) {
                    win.print = noop;
                  }
                  try {
                    if (!win.__autoBetOriginalOpen && typeof win.open === 'function') {
                      win.__autoBetOriginalOpen = win.open.bind(win);
                    }
                    const openWindow = win.__autoBetOriginalOpen;
                    if (typeof openWindow === 'function') {
                      Object.defineProperty(win, 'open', {
                        configurable: true,
                        writable: true,
                        value: (...openArgs) => {
                          const targetUrl = String(openArgs[0] || '');
                          const targetName = String(openArgs[1] || '');
                          if (!targetUrl || /print|receipt|statement|wager|welcome/i.test(targetUrl) || /print|receipt|statement|wager|welcome/i.test(targetName)) {
                            return fakePopup;
                          }
                          const popup = openWindow(...openArgs);
                          try {
                            if (popup) {
                              guardWindow(popup);
                              setTimeout(() => guardWindow(popup), 50);
                              setTimeout(() => guardWindow(popup), 250);
                            }
                          } catch (_) {
                            // Ignore popup access failures.
                          }
                          return popup;
                        }
                      });
                    }
                  } catch (_) {
                    // Ignore window.open guard failures.
                  }
                  try {
                    if (win.document && !win.document.__autoBetOriginalExecCommand && typeof win.document.execCommand === 'function') {
                      win.document.__autoBetOriginalExecCommand = win.document.execCommand.bind(win.document);
                    }
                    const execCommand = win.document?.__autoBetOriginalExecCommand;
                    if (typeof execCommand === 'function') {
                      win.document.execCommand = (...execArgs) => {
                        const command = String(execArgs[0] || '').toLowerCase();
                        if (command === 'print') return false;
                        return execCommand(...execArgs);
                      };
                    }
                  } catch (_) {
                    // Ignore document command guard failures.
                  }
                  win.onbeforeprint = null;
                  win.onafterprint = null;
                  if (win.document) {
                    win.document.onbeforeprint = null;
                    win.document.onafterprint = null;
                  }
                };
                for (const win of accessibleWindows()) {
                  try {
                    guardWindow(win);
                  } catch (_) {
                    // Ignore cross-frame print guard failures.
                  }
                }
                try {
                  if (!window.__autoBetPrintGuardTimer) {
                    window.__autoBetPrintGuardTimer = setInterval(() => {
                      try {
                        disableNativePrint();
                      } catch (_) {
                        // Ignore repeated guard failures.
                      }
                    }, 500);
                  }
                } catch (_) {
                  // Ignore print guard timer failures.
                }
              };
              const readWagerCount = () => {
                const countText = [
                  findElementById('wager_count')?.innerText,
                  findElementById('pc_wager_count')?.innerText
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
              const receiptVerified = (text) => {
                const value = String(text || '');
                if (/Rejected|Failed to Place|not placed|incorrect|maximum|minimum/i.test(value)) return false;
                if (/successfully placed|YOUR BETS HAVE BEEN SUCCESSFULLY PLACED|BET RECEIPT|Ticket No:/i.test(value)) {
                  return true;
                }
                return /Confirmed/i.test(value) && Boolean(extractReceiptReference(value));
              };
              const expectedOpenBetSide = () => {
                const market = String(args.marketType || '').toLowerCase();
                const selection = String(args.selectionName || '').toLowerCase();
                if (market === 'total') {
                  if (selection.includes('大') || selection.includes('over') || selection === 'o') return 'Over';
                  if (selection.includes('小') || selection.includes('under') || selection === 'u') return 'Under';
                }
                return null;
              };
              const expectedOpenBetSelection = () => {
                const market = String(args.marketType || '').toLowerCase();
                const selectionText = String(args.selectionName || '').trim();
                const selection = selectionText.toLowerCase();
                if (market !== 'handicap') return null;
                const tokens = expectedMatchTokens();
                if (selection === 'home' || selection === '主队') return tokens[0] || null;
                if (selection === 'away' || selection === '客队') return tokens[1] || null;
                return selectionText || null;
              };
              const stakeText = () => Number(args.stakeAmount).toFixed(2);
              const expectedMatchTokens = () => String(args.matchTitle || '')
                .split(/\s+vs\s+|\s+v\s+/i)
                .map((part) => part.trim().toLowerCase())
                .filter((part) => part.length >= 2);
              const openBetMatchesExpectedMatch = (text) => {
                const tokens = expectedMatchTokens();
                if (tokens.length < 2) return true;
                const value = String(text || '').toLowerCase();
                return tokens.every((token) => value.includes(token));
              };
              const openBetVerified = (text) => {
                const value = String(text || '');
                if (!/OPEN BETS|Confirmed|Statement/i.test(value)) return false;
                if (!openBetMatchesExpectedMatch(value)) return false;
                const expectedStake = stakeText();
                if (!value.includes('Stake: ' + expectedStake) && !value.includes('Stake:' + expectedStake)) {
                  return false;
                }
                if (args.lineValue && !normalizeLine(value).includes(normalizeLine(args.lineValue))) {
                  return false;
                }
                const side = expectedOpenBetSide();
                if (side && !(new RegExp('\\b' + side + '\\b', 'i')).test(value)) {
                  return false;
                }
                const selectionText = expectedOpenBetSelection();
                if (selectionText && !value.toLowerCase().includes(selectionText.toLowerCase())) {
                  return false;
                }
                return Boolean(extractReceiptReference(value)) || /Confirmed/i.test(value);
              };
              const parseMoney = (value) => {
                const match = String(value || '').replace(/,/g, '').match(/\d+(?:\.\d+)?/);
                return match ? Number(match[0]) : null;
              };
              const parseVerifiedBetSelection = (selectionLine, marketType) => {
                const oddsMatch = String(selectionLine || '').match(/(.+?)\s*@\s*(-?\d+(?:\.\d+)?)/);
                const body = oddsMatch ? oddsMatch[1].trim() : String(selectionLine || '').trim();
                const odds = oddsMatch ? Number(oddsMatch[2]) : null;
                let selectionName = body;
                let lineValue = null;
                if (marketType === 'total') {
                  const totalMatch = body.match(/^(Over|Under|O|U)\s+(.+)$/i);
                  if (totalMatch) {
                    selectionName = /^u/i.test(totalMatch[1]) ? 'Under' : 'Over';
                    lineValue = totalMatch[2].replace(/\s+/g, ' ').trim();
                  }
                } else if (marketType === 'handicap') {
                  const handicapMatch = body.match(/^(.+?)\s+([+-]?\d+(?:\.\d+)?(?:\s*\/\s*[+-]?\d+(?:\.\d+)?)?)$/);
                  if (handicapMatch) {
                    selectionName = handicapMatch[1].trim();
                    lineValue = handicapMatch[2].replace(/\s+/g, ' ').trim();
                  }
                }
                return { selectionName, lineValue, odds };
              };
              const parseVerifiedBetRecord = (text, currentOdds = null) => {
                const lines = String(text || '')
                  .split('\n')
                  .map((line) => line.trim())
                  .filter(Boolean);
                let leagueName = '';
                let matchTitle = '';
                let marketLine = '';
                let selectionLine = '';
                for (let index = 0; index < lines.length - 3; index += 1) {
                  if (/^Soccer\b/i.test(lines[index]) && /\s+v(?:s)?\s+/i.test(lines[index + 2]) && /@\s*-?\d+(?:\.\d+)?/.test(lines[index + 3])) {
                    marketLine = lines[index];
                    leagueName = lines[index + 1];
                    matchTitle = lines[index + 2];
                    selectionLine = lines[index + 3];
                    break;
                  }
                  if (/\s+v(?:s)?\s+/i.test(lines[index + 1]) && /^Soccer\b/i.test(lines[index + 2]) && /@\s*-?\d+(?:\.\d+)?/.test(lines[index + 3])) {
                    leagueName = lines[index];
                    matchTitle = lines[index + 1];
                    marketLine = lines[index + 2];
                    selectionLine = lines[index + 3];
                    break;
                  }
                }
                if (!marketLine || !matchTitle || !selectionLine) return null;
                const marketType = /1\s*X\s*2|Moneyline|Winner/i.test(marketLine)
                  ? 'moneyline'
                  : (/Over\s*\/\s*Under|O\/U|Total/i.test(marketLine) ? 'total' : (/Handicap|HDP/i.test(marketLine) ? 'handicap' : 'other'));
                const parsedSelection = parseVerifiedBetSelection(selectionLine, marketType);
                const ticketReference = extractReceiptReference(text);
                let stakeAmount = null;
                let estimatedWin = null;
                let placedAtText = null;
                for (let index = 0; index < lines.length; index += 1) {
                  const line = lines[index];
                  if (/^Stake:/i.test(line)) stakeAmount = parseMoney(line);
                  if (/^Stake$/i.test(line) && index + 1 < lines.length) stakeAmount = parseMoney(lines[index + 1]);
                  if (/^(?:Est\.\s*Win|To\s+Win):/i.test(line)) estimatedWin = parseMoney(line);
                  if (/^(?:Est\.\s*Win|To\s+Win)$/i.test(line) && index + 1 < lines.length) estimatedWin = parseMoney(lines[index + 1]);
                  if (/\d{1,2}:\d{2}:\d{2}\s*\([A-Z]{2,4}\)/.test(line)) placedAtText = line;
                }
                const odds = Number.isFinite(parsedSelection.odds) ? parsedSelection.odds : currentOdds;
                if (!ticketReference || !Number.isFinite(odds) || !stakeAmount || stakeAmount <= 0) return null;
                return {
                  ticketReference,
                  leagueName,
                  matchTitle,
                  marketType,
                  lineValue: parsedSelection.lineValue,
                  selectionName: parsedSelection.selectionName,
                  odds,
                  stakeAmount,
                  estimatedWin,
                  placedAtText
                };
              };
              const verifiedOpenBetPayload = (text, currentOdds = null) => ({
                placed: true,
                historyVerified: true,
                ticketReference: extractReceiptReference(text),
                message: 'crown_history_verified',
                currentOdds,
                record: parseVerifiedBetRecord(text, currentOdds)
              });
                const clickElement = (element) => {
                  if (!element) return;
                  const fireClick = () => {
                    element.scrollIntoView({ block: 'center', inline: 'center' });
                    const view = ownerWindow(element);
                    const MouseEventCtor = view.MouseEvent || window.MouseEvent;
                    for (const type of ['mouseover', 'mousedown', 'mouseup', 'click']) {
                      element.dispatchEvent(new MouseEventCtor(type, { bubbles: true, cancelable: true, view }));
                    }
                  };
                  setTimeout(fireClick, 0);
                };
                const isVisible = (element) => Boolean(element && (
                  (() => {
                    const view = ownerWindow(element);
                    const style = view.getComputedStyle(element);
                    return style.display !== 'none'
                      && style.visibility !== 'hidden'
                      && style.visibility !== 'collapse'
                      && style.opacity !== '0'
                      && (element.offsetWidth || element.offsetHeight || element.getClientRects().length);
                  })()
                ));
                const stakeInputSelectors = () => [
                  'input#bet_gold_pc',
                  'input#bet_gold',
                  'input[placeholder="Enter Stake"]'
                ];
                const visibleStakeInputs = (...selectors) => {
                  const seen = new Set();
                  return selectors
                    .flatMap((selector) => findAllSelector(selector))
                    .filter((input) => {
                      if (!input || seen.has(input) || !isVisible(input)) return false;
                      seen.add(input);
                      return true;
                    });
                };
                const visibleStakeInput = (...selectors) => visibleStakeInputs(...selectors)[0] || null;
                const parseStakeNumber = (value) => {
                  const parsed = Number(String(value || '').replace(/,/g, '').trim());
                  return Number.isFinite(parsed) ? parsed : null;
                };
                const readStakeNumber = (input) => parseStakeNumber(input?.value) || 0;
                const textMatchesExpectedSlip = (text) => {
                  const value = String(text || '');
                  const lower = value.toLowerCase();
                  const tokens = expectedMatchTokens();
                  if (tokens.length >= 2 && !tokens.every((token) => lower.includes(token))) return false;
                  if (args.lineValue && !normalizeLine(value).includes(normalizeLine(args.lineValue))) return false;
                  const side = expectedOpenBetSide();
                  if (side && !(new RegExp('\\b' + side + '\\b', 'i')).test(value)) return false;
                  const selectionText = expectedOpenBetSelection();
                  if (selectionText && !lower.includes(selectionText.toLowerCase())) return false;
                  return true;
                };
                const nearestStakeContainer = (input) => {
                  let current = input?.parentElement || null;
                  let best = input;
                  for (let depth = 0; current && depth < 10; depth += 1) {
                    const inputs = Array.from(current.querySelectorAll('input')).filter((candidate) => isVisible(candidate));
                    const text = String(current.innerText || current.textContent || '').trim();
                    if (inputs.includes(input) && text) {
                      best = current;
                      if (inputs.length === 1 && /Soccer|Handicap|Over\s*\/\s*Under|HDP|O\/U|@/i.test(text)) {
                        return current;
                      }
                    }
                    if (inputs.length > 1) return best;
                    current = current.parentElement;
                  }
                  return best;
                };
                const stakeInputCandidates = () => visibleStakeInputs(...stakeInputSelectors())
                  .map((input) => {
                    const container = nearestStakeContainer(input);
                    const text = String(container?.innerText || container?.textContent || '');
                    return {
                      input,
                      container,
                      text,
                      matchesExpected: textMatchesExpectedSlip(text),
                      selectionLike: /Soccer|Handicap|Over\s*\/\s*Under|HDP|O\/U|@/i.test(text)
                        && !/^\s*Singles\b/i.test(text)
                    };
                  });
                const findMatchingBetSlipStakeInput = () => {
                  const candidates = stakeInputCandidates();
                  const exact = candidates.find((candidate) => candidate.matchesExpected);
                  if (exact) return exact.input;
                  return candidates.length === 1 ? candidates[0].input : null;
                };
                const betSlipCloseControl = (container) => {
                  if (!container) return null;
                  const containerRect = container.getBoundingClientRect();
                  const controls = Array.from(container.querySelectorAll('button,a,[role="button"],div,span'))
                    .filter((element) => element !== container && isVisible(element));
                  return controls.find((element) => {
                    const text = String(element.innerText || element.textContent || element.value || '').trim();
                    const attrs = [
                      element.id,
                      element.className,
                      element.getAttribute?.('aria-label'),
                      element.getAttribute?.('title')
                    ].filter(Boolean).join(' ');
                    const rect = element.getBoundingClientRect();
                    const nearRightEdge = rect.left >= containerRect.left + (containerRect.width * 0.65);
                    const singleCharCode = text.length === 1 ? text.charCodeAt(0) : null;
                    const closeGlyph = singleCharCode === 215 || singleCharCode === 10005 || singleCharCode === 10006;
                    return /^x$/i.test(text)
                      || closeGlyph
                      || /delete|remove|close|trash|clear|del/i.test(attrs)
                      || (nearRightEdge && (/x/i.test(text) || closeGlyph));
                  }) || null;
                };
                const pruneUnexpectedBetSlipSelections = async () => {
                  const candidates = stakeInputCandidates();
                  if (!candidates.some((candidate) => candidate.matchesExpected)) return false;
                  let removed = false;
                  for (const candidate of candidates) {
                    if (candidate.matchesExpected || !candidate.selectionLike) continue;
                    const closeControl = betSlipCloseControl(candidate.container);
                    if (!closeControl) continue;
                    clickElement(closeControl);
                    removed = true;
                    await sleep(250);
                  }
                  if (removed) await sleep(700);
                  return removed;
                };
                const closedBettingPattern = /currently\s+closed\s+for\s+betting|selection\s+is\s+closed|closed\s+for\s+betting|market\s+closed|not\s+available|suspended/i;
                const expectedBetSlipContainer = () => {
                  const candidates = stakeInputCandidates();
                  const exact = candidates.find((candidate) => candidate.matchesExpected);
                  if (exact?.container) return exact.container;
                  return findAllSelector('div,section,li')
                    .filter((element) => isVisible(element))
                    .map((element) => ({
                      element,
                      text: String(element.innerText || element.textContent || '')
                    }))
                    .find((candidate) => textMatchesExpectedSlip(candidate.text)
                      && (/Soccer|Handicap|Over\s*\/\s*Under|HDP|O\/U|@/i.test(candidate.text)
                        || closedBettingPattern.test(candidate.text)))?.element || null;
                };
                const closeExpectedBetSlipSelection = async () => {
                  const container = expectedBetSlipContainer();
                  const closeControl = betSlipCloseControl(container);
                  if (!closeControl) return false;
                  clickElement(closeControl);
                  await sleep(700);
                  return true;
                };
                const closeClosedBetSlipSelection = async () => {
                  const text = currentText();
                  const rawText = currentRawText();
                  if (!closedBettingPattern.test(text) && !closedBettingPattern.test(rawText)) return false;
                  return closeExpectedBetSlipSelection();
                };
                const failAfterSelection = async (payload) => {
                  await closeExpectedBetSlipSelection();
                  return finish(payload);
                };
                const buttonDisabled = (button) => Boolean(button?.disabled)
                  || String(button?.getAttribute?.('aria-disabled') || '').toLowerCase() === 'true'
                  || /\bdisabled\b/i.test(String(button?.className || ''));
                const buttonTextMatchesPlaceBet = (button) => {
                  const text = String(button?.innerText || button?.textContent || button?.value || '').trim();
                  return /PLACE\s*BET/i.test(text)
                    && !/PLACE\s*BET\s*0(?:\.00)?\s*RMB/i.test(text)
                    && !/Add\s+to\s+Bet\s+Slip/i.test(text);
                };
                const mixedPlaceBetActionBar = (button) => {
                  const text = String(button?.innerText || button?.textContent || button?.value || '').trim();
                  return /Add\s+to\s+Bet\s+Slip/i.test(text)
                    && /PLACE\s*BET/i.test(text)
                    && !/PLACE\s*BET\s*0(?:\.00)?\s*RMB/i.test(text);
                };
                const findPlaceBetButton = () => {
                  const fixedButton = findElementById('order_bet');
                  if (fixedButton && !buttonDisabled(fixedButton) && isVisible(fixedButton)) {
                    const fixedDescendant = Array.from(fixedButton.querySelectorAll('button,input[type="button"],input[type="submit"],[role="button"],a,div,span'))
                      .find((button) => isVisible(button) && !buttonDisabled(button) && buttonTextMatchesPlaceBet(button));
                    if (fixedDescendant) return fixedDescendant;
                    const fixedPlaceButton = buttonTextMatchesPlaceBet(fixedButton) || mixedPlaceBetActionBar(fixedButton)
                      ? fixedButton
                      : null;
                    if (fixedPlaceButton) return fixedPlaceButton;
                  }
                  return findAllSelector('button,input[type="button"],input[type="submit"],[role="button"],a,div,span')
                    .find((button) => isVisible(button) && !buttonDisabled(button) && buttonTextMatchesPlaceBet(button)) || null;
                };
                const placeClickAccepted = () => {
                  const text = currentText();
                  const rawText = currentRawText();
                  if (openBetVerified(rawText || text) || receiptVerified(text)) return true;
                  if (/Rejected|Failed to Place|not placed|incorrect|maximum|minimum/i.test(text)) return true;
                  if (isVisible(findElementById('alert_confirm')) || isVisible(findElementById('C_alert_confirm'))) return true;
                  return false;
                };
                const placeBetClickPoint = (element) => {
                  if (!element) return null;
                  const rect = element.getBoundingClientRect();
                  if (!rect || rect.width <= 0 || rect.height <= 0) return null;
                  const text = String(element.innerText || element.textContent || element.value || '').trim();
                  const x = /Add\s+to\s+Bet\s+Slip/i.test(text) && /PLACE\s*BET/i.test(text)
                    ? rect.left + rect.width * 0.75
                    : rect.left + rect.width / 2;
                  return { x, y: rect.top + rect.height / 2 };
                };
                const absoluteCenter = (element) => {
                  if (!element) return null;
                  try { element.scrollIntoView({ block: 'center', inline: 'center' }); } catch (_) {}
                  let rect = element.getBoundingClientRect();
                  if (!rect || rect.width <= 0 || rect.height <= 0) return null;
                  const point = placeBetClickPoint(element);
                  if (!point) return null;
                  let left = point.x;
                  let top = point.y;
                  let currentWindow = ownerWindow(element);
                  for (let depth = 0; currentWindow && currentWindow !== window && depth < 8; depth += 1) {
                    const frame = currentWindow.frameElement;
                    if (!frame) break;
                    const frameRect = frame.getBoundingClientRect();
                    left += frameRect.left;
                    top += frameRect.top;
                    currentWindow = currentWindow.parent;
                  }
                  return { x: left, y: top };
                };
                const trustedLikeClickElement = (element) => {
                  if (!element) return false;
                  const view = ownerWindow(element);
                  element.scrollIntoView({ block: 'center', inline: 'center' });
                  const point = placeBetClickPoint(element);
                  if (!point) return false;
                  const x = point.x;
                  const y = point.y;
                  const target = element.ownerDocument?.elementFromPoint?.(x, y) || element;
                  const MouseEventCtor = view.MouseEvent || window.MouseEvent;
                  const PointerEventCtor = view.PointerEvent || MouseEventCtor;
                  try { element.focus?.(); } catch (_) {}
                  for (const type of ['pointerover', 'pointermove', 'pointerdown', 'pointerup']) {
                    try {
                      target.dispatchEvent(new PointerEventCtor(type, {
                        bubbles: true,
                        cancelable: true,
                        view,
                        clientX: x,
                        clientY: y,
                        pointerType: 'mouse',
                        button: 0,
                        buttons: type === 'pointerdown' ? 1 : 0
                      }));
                    } catch (_) {}
                  }
                  for (const type of ['mouseover', 'mousemove', 'mousedown', 'mouseup', 'click']) {
                    target.dispatchEvent(new MouseEventCtor(type, {
                      bubbles: true,
                      cancelable: true,
                      view,
                      clientX: x,
                      clientY: y,
                      button: 0,
                      buttons: type === 'mousedown' ? 1 : 0
                    }));
                  }
                  try { target.click?.(); } catch (_) {}
                  if (target !== element) {
                    try { element.click?.(); } catch (_) {}
                  }
                  return true;
                };
                const clickPlaceBetButton = async (initialButton) => {
                  for (let attempt = 0; attempt < 3; attempt += 1) {
                    const button = attempt === 0 && initialButton && isVisible(initialButton)
                      ? initialButton
                      : findPlaceBetButton();
                    if (!button) return false;
                    trustedLikeClickElement(button);
                    await sleep(650);
                    await confirmBetIfPrompted();
                    if (placeClickAccepted()) return true;
                  }
                  return placeClickAccepted();
                };
                const parseCrownOddsText = (value) => {
                  const matches = String(value || '').replace(/,/g, '').match(/-?\d+(?:\.\d+)?/g);
                  if (!matches || matches.length === 0) return null;
                  const numbers = matches.map(Number).filter((number) => Number.isFinite(number));
                  return numbers.length > 0 ? numbers[numbers.length - 1] : null;
                };
                const readBetElementOdds = (element) => {
                  const oddsNodes = Array.from(element.querySelectorAll('.text_odds, [data-ior], [ior], [data-odds], [odds]'));
                  for (const node of oddsNodes) {
                    if (!isVisible(node)) continue;
                    for (const attr of ['data-ior', 'ior', 'data-odds', 'odds']) {
                      const parsed = parseCrownOddsText(node.getAttribute(attr));
                      if (parsed !== null) return parsed;
                    }
                    const parsed = parseCrownOddsText(node.innerText || node.textContent);
                    if (parsed !== null) return parsed;
                  }
                  for (const attr of ['data-ior', 'ior', 'data-odds', 'odds']) {
                    const parsed = parseCrownOddsText(element.getAttribute(attr));
                    if (parsed !== null) return parsed;
                  }
                  return parseCrownOddsText(element.innerText || element.textContent);
                };
                const confirmBetIfPrompted = async () => {
                  const prompts = [
                    { container: 'alert_confirm', yes: 'yes_btn', checkbox: 'confirm_chk' },
                    { container: 'C_alert_confirm', yes: 'C_yes_btn', checkbox: 'C_confirm_chk' }
                  ];
                  for (let index = 0; index < 20; index += 1) {
                    for (const prompt of prompts) {
                      const container = findElementById(prompt.container);
                      if (!isVisible(container)) continue;
                      const checkbox = findElementById(prompt.checkbox);
                      if (checkbox && !checkbox.checked) {
                        checkbox.checked = true;
                        const EventCtor = ownerWindow(checkbox).Event || window.Event;
                        checkbox.dispatchEvent(new EventCtor('change', { bubbles: true }));
                        await sleep(100);
                      }
                      const yesButton = findElementById(prompt.yes);
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
                const closeSuccessfulReceiptPanel = async () => {
                  const receiptMarker = /YOUR BETS HAVE BEEN SUCCESSFULLY PLACED|successfully placed|BET RECEIPT/i;
                  const retainMarker = /Retain Selection/i;
                  const receiptVisible = () => {
                    const text = currentText();
                    const rawText = currentRawText();
                    return receiptMarker.test(text) || receiptMarker.test(rawText) || retainMarker.test(text);
                  };
                  if (!receiptVisible()) return false;
                  const selectors = [
                    '#order_close',
                    '#btn_close',
                    '#close_btn',
                    '.close',
                    '.btn_close',
                    '.order_close',
                    'button',
                    '[role="button"]',
                    'a',
                    'div'
                  ];
                  for (let attempt = 0; attempt < 8; attempt += 1) {
                    const candidates = selectors.flatMap((selector) => findAllSelector(selector));
                    const okButton = candidates.find((element) => {
                      if (!isVisible(element)) return false;
                      const text = String(element.innerText || element.textContent || element.value || '').trim();
                      if (text === 'OK') return true;
                      const id = String(element.id || '');
                      const className = String(element.className || '');
                      return /order_close|close|ok/i.test(id) || /order_close|close|ok/i.test(className);
                    });
                    if (okButton) {
                      clickElement(okButton);
                      await sleep(500);
                      if (!receiptVisible()) return true;
                    }
                    await sleep(250);
                  }
                  return false;
                };
                const betSlipLimitReached = () => {
                  const text = currentText();
                  const rawText = currentRawText();
                  return /最多\s*10\s*个选项|最多十个选项|maximum\s*10|10\s*selections/i.test(text)
                    || /最多\s*10\s*个选项|最多十个选项|maximum\s*10|10\s*selections/i.test(rawText);
                };
                const fillStakeInput = async (stakeInput, stake, accepted = null) => {
                  const view = ownerWindow(stakeInput);
                  const stakeDocument = stakeInput.ownerDocument || document;
                  const EventCtor = view.Event || window.Event;
                  const KeyboardEventCtor = view.KeyboardEvent || window.KeyboardEvent;
                  const InputEventCtor = view.InputEvent || window.InputEvent || EventCtor;
                  const HTMLInputElementCtor = view.HTMLInputElement || window.HTMLInputElement;
                  const findStakeElementById = (id) => stakeDocument.getElementById(id) || findElementById(id);
                  const valueSetter = Object.getOwnPropertyDescriptor(HTMLInputElementCtor.prototype, 'value')?.set;
                  const normalizeStakeValue = (value) => {
                    const parsed = parseStakeNumber(value);
                    return parsed !== null ? String(parsed) : String(value || '').trim();
                  };
                  const expectedStake = normalizeStakeValue(stake);
                  const stakeMatches = () => normalizeStakeValue(stakeInput.value) === expectedStake;
                  const stakeAccepted = async () => {
                    await sleep(250);
                    if (!stakeMatches()) return false;
                    if (!accepted || accepted()) return true;
                    await sleep(500);
                    return stakeMatches() && (!accepted || accepted());
                  };
                  const focusStakeInput = async () => {
                    stakeInput.scrollIntoView({ block: 'center', inline: 'center' });
                    stakeInput.focus();
                    clickElement(stakeInput);
                    await sleep(150);
                  };
                  const setStakeValue = (value) => {
                    if (valueSetter) {
                      valueSetter.call(stakeInput, value);
                    } else {
                      stakeInput.value = value;
                    }
                  };
                  const emitStakeInput = (data = null) => {
                    stakeInput.dispatchEvent(new InputEventCtor('input', {
                      bubbles: true,
                      inputType: data === null ? 'deleteContentBackward' : 'insertText',
                      data
                    }));
                    stakeInput.dispatchEvent(new EventCtor('change', { bubbles: true }));
                  };
                  const applyStakeDirectly = () => {
                    setStakeValue('');
                    emitStakeInput(null);
                    for (const char of stake) {
                      stakeInput.dispatchEvent(new KeyboardEventCtor('keydown', {
                        bubbles: true,
                        cancelable: true,
                        key: char,
                        code: 'Digit' + char
                      }));
                      stakeInput.dispatchEvent(new KeyboardEventCtor('keypress', {
                        bubbles: true,
                        cancelable: true,
                        key: char,
                        code: 'Digit' + char
                      }));
                      setStakeValue(String(stakeInput.value || '') + char);
                      emitStakeInput(char);
                      stakeInput.dispatchEvent(new KeyboardEventCtor('keyup', {
                        bubbles: true,
                        cancelable: true,
                        key: char,
                        code: 'Digit' + char
                      }));
                    }
                    stakeInput.dispatchEvent(new EventCtor('blur', { bubbles: true }));
                    stakeInput.dispatchEvent(new EventCtor('change', { bubbles: true }));
                    return stakeMatches();
                  };
                  const keyboardVisible = () => isVisible(findStakeElementById('num_0'))
                    && isVisible(findStakeElementById('num_done'));
                  await focusStakeInput();
                  if (!keyboardVisible()) {
                    await sleep(500);
                  }
                  if (keyboardVisible()) {
                    for (let index = 0; index < 14; index += 1) {
                      const deleteButton = findStakeElementById('num_x');
                      if (deleteButton) clickElement(deleteButton);
                      await sleep(35);
                    }
                    for (const char of stake) {
                      const numberButton = findStakeElementById('num_' + char);
                      if (!numberButton) return false;
                      clickElement(numberButton);
                      await sleep(80);
                    }
                    return stakeAccepted();
                  }
                  if (applyStakeDirectly()) {
                    if (await stakeAccepted()) return true;
                  }
                  return stakeAccepted();
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
              const menuCandidateIds = () => {
                const phase = String(args.matchPhase || 'live').toLowerCase();
                if (phase === 'prematch') {
                  return ['today_page', 'old_ft_league', 'ft_league', 'old_ft_today_league', 'ft_today_league', 'today'];
                }
                return ['old_ft_live_league', 'ft_live_league', 'live_page'];
              };
              const openTargetSoccerPage = async () => {
                const existing = findElementById(args.betElementId);
                if (existing) return existing;
                const candidates = menuCandidateIds();
                for (const id of candidates) {
                  const element = findElementById(id);
                  if (element) {
                    clickElement(element);
                    await sleep(1200);
                    const found = findElementById(args.betElementId);
                    if (found) return found;
                  }
                  }
                  return waitFor(() => findElementById(args.betElementId), 8000, 300);
                };
                const returnToFootballPage = async () => {
                  const candidates = menuCandidateIds();
                  for (const id of candidates) {
                    const element = findElementById(id);
                    if (element && isVisible(element)) {
                      clickElement(element);
                      await sleep(1000);
                      return true;
                    }
                  }
                  return false;
                };
                disableNativePrint();
                const existingOpenBetText = currentRawText() || currentText();
                if (openBetVerified(existingOpenBetText)) {
                  await returnToFootballPage();
                  return finish(verifiedOpenBetPayload(existingOpenBetText));
                }
                const betElement = await openTargetSoccerPage();
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
              const currentOdds = readBetElementOdds(betElement);
                if (!Number.isFinite(currentOdds)) {
                  return finish({ placed: false, historyVerified: false, message: 'crown_odds_missing' });
                }
                if (currentOdds < Number(args.targetOdds)) {
                  return finish({
                    placed: false,
                    historyVerified: false,
                    message: 'target_odds_below_minimum',
                  currentOdds
                });
              }
  
                const beforeWagerCount = readWagerCount();
                clickElement(betElement);
                await sleep(500);
                if (betSlipLimitReached()) {
                  return await failAfterSelection({ placed: false, historyVerified: false, message: 'crown_betslip_full', currentOdds });
                }
                await waitFor(() => findMatchingBetSlipStakeInput(), 10000, 250);
                await pruneUnexpectedBetSlipSelections();
                const stake = String(Number(args.stakeAmount));
                const stakeSettleBeforePlaceBetMs = 10000;
                const stakeInput = await waitFor(() => findMatchingBetSlipStakeInput(), 10000, 250);
                if (!stakeInput) {
                  if (betSlipLimitReached()) {
                    return await failAfterSelection({ placed: false, historyVerified: false, message: 'crown_betslip_full', currentOdds });
                  }
                  if (await closeClosedBetSlipSelection()) {
                    return finish({ placed: false, historyVerified: false, message: 'crown_selection_closed', currentOdds });
                  }
                  return await failAfterSelection({ placed: false, historyVerified: false, message: 'crown_stake_input_missing', currentOdds });
                }
                const stakeAcceptedBySlip = () => {
                  const expectedStakeAmount = Number(stake);
                  const candidates = stakeInputCandidates();
                  const exactStakeCandidates = candidates.filter((candidate) =>
                    readStakeNumber(candidate.input) === expectedStakeAmount
                  );
                  const filledExpectedSelection = exactStakeCandidates.find((candidate) => candidate.matchesExpected)
                    || (exactStakeCandidates.length === 1 ? exactStakeCandidates[0] : null);
                  if (!filledExpectedSelection) return false;
                  const unexpectedFilledSelection = candidates.some((candidate) =>
                    candidate !== filledExpectedSelection && candidate.selectionLike && readStakeNumber(candidate.input) > 0
                  );
                  if (unexpectedFilledSelection) return false;
                  const button = findPlaceBetButton();
                  if (button) return true;
                  const pageText = currentText();
                  return /PLACE\s*BET/i.test(pageText)
                    && !/PLACE\s*BET\s*0(?:\.00)?\s*RMB/i.test(pageText);
                };
                const stakeFilled = await fillStakeInput(stakeInput, stake, stakeAcceptedBySlip);
                if (!stakeFilled) {
                  return await failAfterSelection({ placed: false, historyVerified: false, message: 'crown_stake_input_not_applied', currentOdds });
                }
                await sleep(stakeSettleBeforePlaceBetMs);
  
                let orderButton = await waitFor(() => {
                  return findPlaceBetButton();
                }, 4000, 250);
                if (!orderButton) {
                  const pageText = currentText();
                  const rawPageText = currentRawText();
                  if (openBetVerified(rawPageText || pageText)) {
                    return finish(verifiedOpenBetPayload(rawPageText || pageText, currentOdds));
                  }
                  if (/minimum stake|min(?:imum)?\\.? stake/i.test(pageText)) {
                    return await failAfterSelection({ placed: false, historyVerified: false, message: 'crown_stake_below_minimum', currentOdds });
                  }
                  if (/maximum stake|max(?:imum)?\\.? stake/i.test(pageText)) {
                    return await failAfterSelection({ placed: false, historyVerified: false, message: 'crown_stake_above_maximum', currentOdds });
                  }
                  if (betSlipLimitReached()) {
                    return await failAfterSelection({ placed: false, historyVerified: false, message: 'crown_betslip_full', currentOdds });
                  }
                  if (await closeClosedBetSlipSelection()) {
                    return finish({ placed: false, historyVerified: false, message: 'crown_selection_closed', currentOdds });
                  }
                  return await failAfterSelection({ placed: false, historyVerified: false, message: 'crown_place_button_disabled', currentOdds });
                }
                disableNativePrint();
                const finalPlaceButton = findPlaceBetButton() || orderButton;
                const nativePlaceButtonPoint = absoluteCenter(finalPlaceButton);
                if (nativePlaceButtonPoint) {
                  return finish({
                    placed: false,
                    historyVerified: false,
                    message: 'crown_place_button_native_click_required',
                    currentOdds,
                    placeButton: nativePlaceButtonPoint
                  });
                }
                return await failAfterSelection({
                  placed: false,
                  historyVerified: false,
                  message: 'crown_place_button_click_failed',
                  currentOdds,
                  placeButton: nativePlaceButtonPoint
                });
              disableNativePrint();
              await confirmBetIfPrompted();
              disableNativePrint();

              const receiptText = await waitFor(() => {
                disableNativePrint();
                const confirmVisible = isVisible(findElementById('alert_confirm'))
                  || isVisible(findElementById('C_alert_confirm'));
                if (confirmVisible) {
                  confirmBetIfPrompted();
                }
                const text = currentText();
                const rawText = currentRawText();
                if (openBetVerified(rawText || text)) return rawText || text;
                if (receiptVerified(text)) return text;
                if (/Rejected|Failed to Place|not placed|incorrect|maximum|minimum/i.test(text)) return text;
                return null;
              }, 18000, 500);
              if (receiptText && openBetVerified(receiptText)) {
                await closeSuccessfulReceiptPanel();
                await returnToFootballPage();
                return finish(verifiedOpenBetPayload(receiptText, currentOdds));
              }
              if (!receiptText || !receiptVerified(receiptText)) {
                return await failAfterSelection({
                  placed: false,
                  historyVerified: false,
                  message: 'crown_bet_not_confirmed',
                  currentOdds,
                  pageText: String(receiptText || currentText()).slice(0, 500)
                });
              }
              const ticketReference = extractReceiptReference(receiptText);
              if (ticketReference && receiptVerified(receiptText)) {
                await closeSuccessfulReceiptPanel();
                await returnToFootballPage();
                return finish({
                  placed: true,
                  historyVerified: true,
                  ticketReference,
                  message: 'crown_receipt_verified',
                  currentOdds,
                  record: parseVerifiedBetRecord(receiptText, currentOdds)
                });
              }

              const historyButton = findElementById('header_todaywagers')
                || findElementById('menu_todaywagers');
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
                if (openBetVerified(rawText || text)) {
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
                await returnToFootballPage();
                return finish({
                  placed: true,
                  historyVerified: false,
                  ticketReference,
                  message: 'crown_history_unverified',
                  currentOdds
                });
              }
              const verifiedTicketReference = ticketReference || extractReceiptReference(historyText);
              await closeSuccessfulReceiptPanel();
              await returnToFootballPage();
              return finish({
                placed: true,
                historyVerified: true,
                ticketReference: verifiedTicketReference,
                message: 'crown_history_verified',
                currentOdds,
                record: parseVerifiedBetRecord(historyText, currentOdds)
              });
            })()
        """.trimIndent()
    }

    private fun crownBetHistoryVerificationScript(argsJson: String, ticketReferenceJson: String): String {
        return """
            (async () => {
              const args = $argsJson;
              const ticketReference = $ticketReferenceJson;
              const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
              const accessibleWindows = () => {
                const windows = [];
                const seen = new Set();
                const visit = (win) => {
                  if (!win || seen.has(win)) return;
                  seen.add(win);
                  try {
                    if (win.document) windows.push(win);
                    for (const frame of Array.from(win.frames || [])) visit(frame);
                  } catch (_) {
                    // Ignore cross-origin frames.
                  }
                };
                visit(window);
                return windows;
              };
              const documents = () => accessibleWindows().map((win) => win.document).filter(Boolean);
              const byId = (id) => {
                for (const doc of documents()) {
                  const element = doc.getElementById(id);
                  if (element) return element;
                }
                return null;
              };
              const ownerWindow = (element) => element?.ownerDocument?.defaultView || window;
              const isVisible = (element) => Boolean(element && (() => {
                const view = ownerWindow(element);
                const style = view.getComputedStyle(element);
                return style.display !== 'none'
                  && style.visibility !== 'hidden'
                  && style.visibility !== 'collapse'
                  && style.opacity !== '0'
                  && (element.offsetWidth || element.offsetHeight || element.getClientRects().length);
              })());
              const allSelector = (selector) => documents().flatMap((doc) => Array.from(doc.querySelectorAll(selector)));
              const clickElement = (element) => {
                if (!element) return false;
                const view = ownerWindow(element);
                const MouseEventCtor = view.MouseEvent || window.MouseEvent;
                try { element.scrollIntoView({ block: 'center', inline: 'center' }); } catch (_) {}
                const rect = element.getBoundingClientRect();
                const x = rect.left + rect.width / 2;
                const y = rect.top + rect.height / 2;
                const target = element.ownerDocument?.elementFromPoint?.(x, y) || element;
                for (const type of ['mouseover', 'mousemove', 'mousedown', 'mouseup', 'click']) {
                  try {
                    target.dispatchEvent(new MouseEventCtor(type, {
                      bubbles: true,
                      cancelable: true,
                      view,
                      clientX: x,
                      clientY: y,
                      button: 0,
                      buttons: type === 'mousedown' ? 1 : 0
                    }));
                  } catch (_) {}
                }
                try { target.click?.(); } catch (_) {}
                if (target !== element) {
                  try { element.click?.(); } catch (_) {}
                }
                return true;
              };
              const nativePoint = (element) => {
                if (!element) return null;
                try { element.scrollIntoView({ block: 'center', inline: 'center' }); } catch (_) {}
                const rect = element.getBoundingClientRect();
                if (!rect || rect.width <= 0 || rect.height <= 0) return null;
                let left = rect.left + rect.width / 2;
                let top = rect.top + rect.height / 2;
                let currentWindow = ownerWindow(element);
                for (let depth = 0; currentWindow && currentWindow !== window && depth < 8; depth += 1) {
                  const frame = currentWindow.frameElement;
                  if (!frame) break;
                  const frameRect = frame.getBoundingClientRect();
                  left += frameRect.left;
                  top += frameRect.top;
                  currentWindow = currentWindow.parent;
                }
                return { x: left, y: top };
              };
              const text = () => documents().map((doc) => doc.body?.innerText || '').join('\n');
              const rawText = () => documents().map((doc) => doc.documentElement?.innerText || '').join('\n');
              const menuCandidateIds = () => {
                const phase = String(args.matchPhase || 'live').toLowerCase();
                if (phase === 'prematch') {
                  return ['today_page', 'old_ft_league', 'ft_league', 'old_ft_today_league', 'ft_today_league', 'today'];
                }
                return ['old_ft_live_league', 'ft_live_league', 'live_page'];
              };
              const returnToFootballPage = async () => {
                for (const id of menuCandidateIds()) {
                  const element = byId(id);
                  if (element && isVisible(element)) {
                    clickElement(element);
                    await sleep(800);
                    return true;
                  }
                }
                return false;
              };
              const confirmBetIfPrompted = async () => {
                const prompts = [
                  { container: 'alert_confirm', yes: 'yes_btn', checkbox: 'confirm_chk' },
                  { container: 'C_alert_confirm', yes: 'C_yes_btn', checkbox: 'C_confirm_chk' }
                ];
                for (let index = 0; index < 20; index += 1) {
                  for (const prompt of prompts) {
                    const container = byId(prompt.container);
                    if (!isVisible(container)) continue;
                    const checkbox = byId(prompt.checkbox);
                    if (checkbox && !checkbox.checked) {
                      checkbox.checked = true;
                      const EventCtor = ownerWindow(checkbox).Event || window.Event;
                      checkbox.dispatchEvent(new EventCtor('change', { bubbles: true }));
                      await sleep(100);
                    }
                    const yesButton = byId(prompt.yes);
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
              const receiptSuccessMarker = /YOUR BETS HAVE BEEN SUCCESSFULLY PLACED|successfully placed|BET RECEIPT/i;
              const receiptOkButtonPoint = async () => {
                const receiptVisible = () => receiptSuccessMarker.test(text()) || receiptSuccessMarker.test(rawText()) || /Retain Selection/i.test(text());
                if (!receiptVisible()) return false;
                for (let attempt = 0; attempt < 8; attempt += 1) {
                  const candidates = [
                    byId('order_close'),
                    byId('btn_close'),
                    byId('close_btn'),
                    ...allSelector('.close,.btn_close,.order_close,button,[role="button"],a,div')
                  ].filter(Boolean);
                  const okButton = candidates.find((element) => {
                    if (!isVisible(element)) return false;
                    const label = String(element.innerText || element.textContent || element.value || '').trim();
                    const id = String(element.id || '');
                    const className = String(element.className || '');
                    const combined = [label, id, className].join(' ');
                    if (/My\s*Bets|Open\s*Bets|Statement|Wager/i.test(combined)) return false;
                    return label === 'OK' || /order_close|close|ok/i.test(id) || /order_close|close|ok/i.test(className);
                  });
                  if (okButton) {
                    return nativePoint(okButton);
                  }
                  await sleep(250);
                }
                return null;
              };
              const normalizeLine = (value) => String(value || '').replace(/\s+/g, '').replace(/[０-９]/g, (char) => String.fromCharCode(char.charCodeAt(0) - 65248));
              const extractReceiptReference = (value) => {
                const ticketMatch = String(value || '').match(/Ticket No:\s*([A-Za-z0-9-]+)/i);
                if (ticketMatch) return ticketMatch[1];
                const fantasyMatch = String(value || '').match(/\b(?:OU|HDP|FS|FT|BK)[A-Z0-9]*\d{6,}\b/i);
                return fantasyMatch ? fantasyMatch[0] : null;
              };
              const matchTokens = () => String(args.matchTitle || '')
                .split(/\s+vs\s+|\s+v\s+/i)
                .map((part) => part.trim().toLowerCase())
                .filter((part) => part.length >= 2);
              const stakeText = () => Number(args.stakeAmount).toFixed(2);
              const expectedOpenBetSide = () => {
                const market = String(args.marketType || '').toLowerCase();
                const selection = String(args.selectionName || '').toLowerCase();
                if (market === 'total') {
                  if (selection.includes('大') || selection.includes('over') || selection === 'o') return 'Over';
                  if (selection.includes('小') || selection.includes('under') || selection === 'u') return 'Under';
                }
                return null;
              };
              const expectedOpenBetSelection = () => {
                const market = String(args.marketType || '').toLowerCase();
                const selectionText = String(args.selectionName || '').trim();
                const selection = selectionText.toLowerCase();
                if (market !== 'handicap') return null;
                const tokens = matchTokens();
                if (selection === 'home' || selection === '主队') return tokens[0] || null;
                if (selection === 'away' || selection === '客队') return tokens[1] || null;
                return selectionText || null;
              };
              const historyVerified = (value) => {
                const content = String(value || '');
                const lower = content.toLowerCase();
                if (ticketReference && content.includes(ticketReference)) return true;
                if (receiptSuccessMarker.test(content) && extractReceiptReference(content)) return true;
                const tokens = matchTokens();
                if (tokens.length >= 2 && !tokens.every((token) => lower.includes(token))) return false;
                if (args.lineValue && !normalizeLine(content).includes(normalizeLine(args.lineValue))) return false;
                if (!content.includes(stakeText()) && !content.includes(String(Number(args.stakeAmount)))) return false;
                const side = expectedOpenBetSide();
                if (side && !(new RegExp('\\b' + side + '\\b', 'i')).test(content)) return false;
                const selection = expectedOpenBetSelection();
                if (selection && !lower.includes(selection.toLowerCase())) return false;
                return /OPEN BETS|Open Bets|Confirmed|Statement|My Bets|Ticket No:/i.test(content);
              };
              const parseMoney = (value) => {
                const match = String(value || '').replace(/,/g, '').match(/\d+(?:\.\d+)?/);
                return match ? Number(match[0]) : null;
              };
              const parseSelection = (selectionLine, marketType) => {
                const oddsMatch = String(selectionLine || '').match(/(.+?)\s*@\s*(-?\d+(?:\.\d+)?)/);
                const body = oddsMatch ? oddsMatch[1].trim() : String(selectionLine || '').trim();
                const odds = oddsMatch ? Number(oddsMatch[2]) : null;
                let selectionName = body;
                let lineValue = null;
                if (marketType === 'total') {
                  const totalMatch = body.match(/^(Over|Under|O|U)\s+(.+)$/i);
                  if (totalMatch) {
                    selectionName = /^u/i.test(totalMatch[1]) ? 'Under' : 'Over';
                    lineValue = totalMatch[2].replace(/\s+/g, ' ').trim();
                  }
                } else if (marketType === 'handicap') {
                  const handicapMatch = body.match(/^(.+?)\s+([+-]?\d+(?:\.\d+)?(?:\s*\/\s*[+-]?\d+(?:\.\d+)?)?)$/);
                  if (handicapMatch) {
                    selectionName = handicapMatch[1].trim();
                    lineValue = handicapMatch[2].replace(/\s+/g, ' ').trim();
                  }
                }
                return { selectionName, lineValue, odds };
              };
              const parseVerifiedBetRecord = (value) => {
                const lines = String(value || '').split('\n').map((line) => line.trim()).filter(Boolean);
                let leagueName = '';
                let matchTitle = '';
                let marketLine = '';
                let selectionLine = '';
                for (let index = 0; index < lines.length - 3; index += 1) {
                  if (/^Soccer\b/i.test(lines[index]) && /\s+v(?:s)?\s+/i.test(lines[index + 2]) && /@\s*-?\d+(?:\.\d+)?/.test(lines[index + 3])) {
                    marketLine = lines[index];
                    leagueName = lines[index + 1];
                    matchTitle = lines[index + 2];
                    selectionLine = lines[index + 3];
                    break;
                  }
                  if (/\s+v(?:s)?\s+/i.test(lines[index + 1]) && /^Soccer\b/i.test(lines[index + 2]) && /@\s*-?\d+(?:\.\d+)?/.test(lines[index + 3])) {
                    leagueName = lines[index];
                    matchTitle = lines[index + 1];
                    marketLine = lines[index + 2];
                    selectionLine = lines[index + 3];
                    break;
                  }
                }
                if (!marketLine || !matchTitle || !selectionLine) return null;
                const marketType = /1\s*X\s*2|Moneyline|Winner/i.test(marketLine)
                  ? 'moneyline'
                  : (/Over\s*\/\s*Under|O\/U|Total/i.test(marketLine) ? 'total' : (/Handicap|HDP/i.test(marketLine) ? 'handicap' : 'other'));
                const parsedSelection = parseSelection(selectionLine, marketType);
                const ticket = extractReceiptReference(value) || ticketReference || null;
                let stakeAmount = null;
                let estimatedWin = null;
                let placedAtText = null;
                for (let index = 0; index < lines.length; index += 1) {
                  const line = lines[index];
                  if (/^Stake:/i.test(line)) stakeAmount = parseMoney(line);
                  if (/^Stake$/i.test(line) && index + 1 < lines.length) stakeAmount = parseMoney(lines[index + 1]);
                  if (/^(?:Est\.\s*Win|To\s+Win):/i.test(line)) estimatedWin = parseMoney(line);
                  if (/^(?:Est\.\s*Win|To\s+Win)$/i.test(line) && index + 1 < lines.length) estimatedWin = parseMoney(lines[index + 1]);
                  if (/\d{1,2}:\d{2}:\d{2}\s*\([A-Z]{2,4}\)/.test(line)) placedAtText = line;
                }
                if (!ticket || !Number.isFinite(parsedSelection.odds) || !stakeAmount || stakeAmount <= 0) return null;
                return {
                  ticketReference: ticket,
                  leagueName,
                  matchTitle,
                  marketType,
                  lineValue: parsedSelection.lineValue,
                  selectionName: parsedSelection.selectionName,
                  odds: parsedSelection.odds,
                  stakeAmount,
                  estimatedWin,
                  placedAtText
                };
              };
              const result = async (verified, value) => {
                let receiptOkButton = null;
                if (verified) {
                  await confirmBetIfPrompted();
                  receiptOkButton = await receiptOkButtonPoint();
                  if (!receiptOkButton) await returnToFootballPage();
                }
                return JSON.stringify({
                  placed: true,
                  historyVerified: verified,
                  ticketReference: extractReceiptReference(value) || ticketReference || null,
                  message: verified ? 'crown_history_verified' : 'crown_history_unverified',
                  record: verified ? parseVerifiedBetRecord(value) : null,
                  receiptOkButton: receiptOkButton
                });
              };

              await sleep(600);
              await confirmBetIfPrompted();
              const initial = rawText() || text();
              if (historyVerified(initial)) return await result(true, initial);

              const historyButton = byId('header_todaywagers') || byId('menu_todaywagers');
              if (historyButton) {
                historyButton.scrollIntoView({ block: 'center', inline: 'center' });
                clickElement(historyButton);
                await sleep(1800);
              }

              const deadline = Date.now() + 15000;
              while (Date.now() <= deadline) {
                await confirmBetIfPrompted();
                const current = rawText() || text();
                if (historyVerified(current)) return await result(true, current);
                await sleep(500);
              }
              return await result(false, rawText() || text());
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

    private fun com.fasterxml.jackson.databind.JsonNode.decimalOrNull(): BigDecimal? {
        if (isMissingNode || isNull) return null
        return runCatching { BigDecimal(asText().replace(",", "").trim()) }.getOrNull()
    }

    private fun com.fasterxml.jackson.databind.JsonNode.toCrownBetPlacementResult(): CrownBetPlacementResult {
        return CrownBetPlacementResult(
            placed = path("placed").asBoolean(false),
            historyVerified = path("historyVerified").asBoolean(false),
            ticketReference = path("ticketReference").textOrNull(),
            message = path("message").textOrNull() ?: "crown_bet_failed",
            currentOdds = path("currentOdds").decimalOrNull(),
            verifiedRecord = path("record").crownOpenBetRecordOrNull()
        )
    }

    private fun com.fasterxml.jackson.databind.JsonNode.crownOpenBetRecordOrNull(): CrownOpenBetRecord? {
        if (isMissingNode || isNull || !isObject) return null
        val odds = path("odds").decimalOrNull() ?: return null
        val stakeAmount = path("stakeAmount").decimalOrNull() ?: return null
        val ticketReference = path("ticketReference").textOrNull()
        if (ticketReference.isNullOrBlank() || stakeAmount <= BigDecimal.ZERO) return null
        return CrownOpenBetRecord(
            ticketReference = ticketReference,
            leagueName = path("leagueName").textOrNull().orEmpty(),
            matchTitle = path("matchTitle").textOrNull().orEmpty(),
            marketType = path("marketType").textOrNull().orEmpty(),
            lineValue = path("lineValue").textOrNull(),
            selectionName = path("selectionName").textOrNull().orEmpty(),
            odds = odds,
            stakeAmount = stakeAmount,
            estimatedWin = path("estimatedWin").decimalOrNull(),
            placedAtText = path("placedAtText").textOrNull()
        )
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

    private fun String?.isUsableCrownPageCandidate(): Boolean {
        val raw = this?.trim()?.takeIf { it.isNotBlank() } ?: return false
        val uri = runCatching { URI(raw) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase()
        val host = uri.host?.trim()?.lowercase() ?: return false
        if (scheme != "http" && scheme != "https") return false
        if (host == "start.adspower.net" || host.endsWith(".adspower.net")) return false
        return true
    }

    private fun String?.hostEquals(host: String?): Boolean {
        val normalized = hostFromUrl(this)
        return !normalized.isNullOrBlank() && !host.isNullOrBlank() && normalized == host
    }

    private data class BrowserTarget(
        val id: String?,
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

    private data class CrownPageActivationResult(
        val target: BrowserTarget,
        val wsUrl: String,
        val snapshot: CrownPageSnapshot
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
