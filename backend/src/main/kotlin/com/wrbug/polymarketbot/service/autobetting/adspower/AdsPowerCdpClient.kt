package com.wrbug.polymarketbot.service.autobetting.adspower

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.wrbug.polymarketbot.service.autobetting.crown.CROWN_NETWORK_UNSTABLE_STATUS
import com.wrbug.polymarketbot.service.autobetting.crown.CrownSessionPageAnalyzer
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.net.URI
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

private const val CROWN_BET_PLACEMENT_TIMEOUT_SECONDS = 45L
private val nativePlaceBetClickReasons = setOf(
    "crown_place_button_native_click_required",
    "crown_place_button_click_failed",
    "crown_bet_not_confirmed"
)

class AdsPowerCdpClient(
    private val objectMapper: ObjectMapper
) {
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(Duration.ofSeconds(3))
        .readTimeout(Duration.ofSeconds(8))
        .writeTimeout(Duration.ofSeconds(8))
        .build()

    internal fun activateCrownPageBeforePlacement(
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

    internal fun dismissCrownNetworkPrompt(wsUrl: String): Boolean {
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

    internal fun readCrownPageSnapshot(debugPort: String, loginUrl: String?): CrownPageSnapshot? {
        val target = readCrownPageTarget(debugPort, loginUrl) ?: return null
        val wsUrl = target.webSocketDebuggerUrl?.trim().orEmpty()
        if (wsUrl.isBlank()) {
            return CrownPageSnapshot(target.pageUrl.orEmpty(), target.title.orEmpty(), "")
        }
        return readPageSnapshotViaCdp(wsUrl, target)
    }

    internal fun readCrownPageSnapshots(debugPort: String, loginUrl: String?): List<CrownPageSnapshot> {
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

    internal fun readCrownPageTarget(debugPort: String, loginUrl: String?): BrowserTarget? {
        return readCrownPageTargets(debugPort, loginUrl).firstOrNull()
    }

    internal fun readCrownPageTargets(debugPort: String, loginUrl: String?): List<BrowserTarget> {
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

    internal fun installCrownPrintGuard(wsUrl: String) {
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

    internal fun closeCrownPrintTargets(debugPort: String) {
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

    internal fun selectCrownTarget(targets: List<BrowserTarget>, loginUrl: String?): BrowserTarget? {
        return selectCrownTargets(targets, loginUrl).firstOrNull()
    }

    internal fun selectCrownTargets(targets: List<BrowserTarget>, loginUrl: String?): List<BrowserTarget> {
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

    internal fun evaluateCrownPageJson(wsUrl: String, expression: String): String? {
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

    internal fun dispatchNativePlaceBetClick(
        wsUrl: String,
        result: com.fasterxml.jackson.databind.JsonNode
    ): Boolean = dispatchNativeJsonPointClick(wsUrl, result, "placeButton")

    internal fun dispatchNativeReceiptOkClick(
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

    internal fun shouldDispatchNativePlaceBetClick(
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

    private fun JsonNode.textOrNull(): String? {
        return takeIf { !it.isMissingNode && !it.isNull }?.asText()?.takeIf { it.isNotBlank() }
    }
}

internal data class BrowserTarget(
    val id: String?,
    val type: String?,
    val title: String?,
    val pageUrl: String?,
    val webSocketDebuggerUrl: String?
)

internal data class CrownPageSnapshot(
    val pageUrl: String,
    val title: String,
    val text: String
)

internal data class CrownPageActivationResult(
    val target: BrowserTarget,
    val wsUrl: String,
    val snapshot: CrownPageSnapshot
)
