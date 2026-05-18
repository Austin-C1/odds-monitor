package com.wrbug.polymarketbot.service.oddsmonitor.collector.crown

import com.wrbug.polymarketbot.entity.OddsDataSourceConfig
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.nio.charset.Charset
import java.math.BigDecimal
import java.util.concurrent.TimeUnit

@Component
open class CrownApiClient @Autowired constructor(
    private val parser: CrownResponseParser,
    @Value("\${odds-monitor.crown.fetch-timeout-millis:55000}")
    private val fetchTimeoutMillis: Long,
    @Value("\${odds-monitor.crown.http-call-timeout-seconds:20}")
    private val httpCallTimeoutSeconds: Long
) : CrownMatchGateway {
    private var nowMillis: () -> Long = System::currentTimeMillis

    internal constructor(
        parser: CrownResponseParser,
        fetchTimeoutMillis: Long,
        httpCallTimeoutSeconds: Long,
        nowMillis: () -> Long
    ) : this(parser, fetchTimeoutMillis, httpCallTimeoutSeconds) {
        this.nowMillis = nowMillis
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(httpCallTimeoutSeconds.coerceAtLeast(1), TimeUnit.SECONDS)
        .build()

    override fun login(config: OddsDataSourceConfig): CrownSession {
        return performLogin(config).second
    }

    fun checkAccount(config: OddsDataSourceConfig): CrownAccountCheckResult {
        var session: CrownSession? = null
        return try {
            val (_, activeSession) = performLogin(config)
            session = activeSession
            val balance = fetchAccountBalance(session)
                ?: throw CrownCollectionException("failed_balance", "登录正常，但余额未返回，检测后已退出")
            CrownAccountCheckResult(
                status = "success",
                balance = balance,
                message = accountCheckMessage()
            )
        } catch (ex: CrownCollectionException) {
            CrownAccountCheckResult(
                status = "error",
                balance = null,
                message = ex.message
            )
        } catch (ex: Exception) {
            CrownAccountCheckResult(
                status = "error",
                balance = null,
                message = ex.message ?: "crown account check failed"
            )
        } finally {
            if (session != null) {
                logout(session)
            }
        }
    }

    private fun accountCheckMessage(): String {
        return "登录正常，余额已获取，检测后已退出"
    }

    private fun performLogin(config: OddsDataSourceConfig): Pair<CrownLoginResponse, CrownSession> {
        val username = config.username?.takeIf { it.isNotBlank() }
            ?: throw CrownCollectionException("failed_config", "crown username is empty")
        val password = config.password?.takeIf { it.isNotBlank() }
            ?: throw CrownCollectionException("failed_config", "crown password is empty")
        val baseUrl = config.crownBaseUrl()

        val cookieJar = linkedMapOf<String, String>()
        val response = postForm(
            baseUrl = baseUrl,
            path = "/transform_nl.php",
            form = mapOf(
                "p" to "chk_login",
                "langx" to "zh-cn",
                "username" to username,
                "password" to password,
                "app" to "N",
                "auto" to "CDBHGD",
                "blackbox" to ""
            ),
            cookies = cookieJar
        )
        val login = parser.parseLogin(response)
        if (login.status != "200" || login.uid.isNullOrBlank()) {
            val details = listOfNotNull(login.messageCode, login.message).joinToString(": ")
            val suffix = details.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()
            throw CrownCollectionException("failed_login", "crown login failed with status ${login.status}$suffix")
        }
        val session = CrownSession(
            uid = login.uid,
            cookies = cookieJar.toMap(),
            username = username.trim(),
            baseUrl = baseUrl,
            savedAt = System.currentTimeMillis()
        )
        return login to session
    }

    private fun fetchAccountBalance(session: CrownSession): BigDecimal? {
        val cookieJar = session.cookies.toMutableMap()
        val response = postForm(
            baseUrl = session.baseUrl?.takeIf { it.isNotBlank() } ?: DEFAULT_BASE_URL,
            path = "/transform.php",
            form = mapOf(
                "uid" to session.uid,
                "langx" to "zh-cn",
                "p" to "get_member_data",
                "change" to "all"
            ),
            cookies = cookieJar
        )
        return parser.parseAccountBalance(response)
    }

    fun fetchMatches(config: OddsDataSourceConfig, session: CrownSession): List<CrownFootballMatch> {
        return fetchMatchesWithSession(config, session).matches
    }

    override fun fetchMatchesWithSession(config: OddsDataSourceConfig, session: CrownSession): CrownFetchResult {
        val deadlineAt = nowMillis() + fetchTimeoutMillis.coerceAtLeast(1)
        val baseUrl = session.baseUrl?.takeIf { it.isNotBlank() } ?: config.crownBaseUrl()
        val cookieJar = session.cookies.toMutableMap()
        val listResponse = postForm(
            baseUrl = baseUrl,
            path = "/transform.php",
            form = mapOf(
                "uid" to session.uid,
                "langx" to "zh-cn",
                "p" to "get_game_list",
                "p3type" to "",
                "date" to "",
                "gtype" to "ft",
                "showtype" to "today",
                "rtype" to "r",
                "ltype" to "3",
                "filter" to "MIX",
                "cupFantasy" to "N",
                "sorttype" to "L",
                "specialClick" to "",
                "isFantasy" to "N"
            ),
            cookies = cookieJar
        )
        parser.parseSessionFailure(listResponse)?.let { reason ->
            throw CrownCollectionException("failed_login", "crown session expired: $reason")
        }

        val directMatches = parser.parseDetailGames(listResponse, isLive = false)
        if (directMatches.isNotEmpty()) {
            return CrownFetchResult(
                matches = directMatches,
                session = session.copy(cookies = cookieJar.toMap(), savedAt = System.currentTimeMillis())
            )
        }

        val matches = mutableListOf<CrownFootballMatch>()
        parser.parseGameList(listResponse)
            .distinctBy { CrownGameDetailKey(it.lid, it.detailId, it.isLive) }
            .forEach { item ->
                ensureWithinFetchBudget(deadlineAt)
                val detailResponse = postForm(
                    baseUrl = baseUrl,
                    path = "/transform.php",
                    form = mapOf(
                        "uid" to session.uid,
                        "langx" to "zh-cn",
                        "p" to "get_game_more",
                        "gtype" to "ft",
                        "showtype" to if (item.isLive) "live" else "today",
                        "isRB" to (item.isRb ?: if (item.isLive) "Y" else "N"),
                        "lid" to item.lid,
                        "ecid" to item.detailId,
                        "filter" to "All",
                        "mode" to "NORMAL",
                        "from" to "game_more"
                    ),
                    cookies = cookieJar
                )
                parser.parseSessionFailure(detailResponse)?.let { reason ->
                    throw CrownCollectionException("failed_login", "crown session expired: $reason")
                }
                matches += parser.parseDetailGames(detailResponse, item.isLive, item.elapsedMinutes)
            }
        return CrownFetchResult(
            matches = matches,
            session = session.copy(cookies = cookieJar.toMap(), savedAt = System.currentTimeMillis())
        )
    }

    private fun ensureWithinFetchBudget(deadlineAt: Long) {
        if (nowMillis() >= deadlineAt) {
            throw CrownCollectionException(
                "failed_timeout",
                "crown collection exceeded ${fetchTimeoutMillis.coerceAtLeast(1) / 1000} seconds"
            )
        }
    }

    protected open fun postForm(
        baseUrl: String,
        path: String,
        form: Map<String, String>,
        cookies: MutableMap<String, String>
    ): String {
        val bodyBuilder = FormBody.Builder(Charsets.UTF_8)
        form.forEach { (key, value) -> bodyBuilder.add(key, value) }

        val requestBuilder = Request.Builder()
            .url(baseUrl.trimEnd('/') + path)
            .post(bodyBuilder.build())
            .header("Accept", "application/xml,text/xml,*/*")
            .header("User-Agent", "Mozilla/5.0")

        if (cookies.isNotEmpty()) {
            requestBuilder.header("Cookie", cookies.entries.joinToString("; ") { "${it.key}=${it.value}" })
        }

        httpClient.newCall(requestBuilder.build()).execute().use { response ->
            updateCookies(response, cookies)
            if (!response.isSuccessful) {
                throw CrownCollectionException("failed_http", "crown http ${response.code}")
            }
            return decodeResponseBody(response)
        }
    }

    private fun logout(session: CrownSession) {
        runCatching {
            postForm(
                baseUrl = session.baseUrl?.takeIf { it.isNotBlank() } ?: DEFAULT_BASE_URL,
                path = "/transform_nl.php",
                form = mapOf(
                    "uid" to session.uid,
                    "langx" to "zh-cn",
                    "p" to "logout"
                ),
                cookies = session.cookies.toMutableMap()
            )
        }
    }

    private fun decodeResponseBody(response: Response): String {
        val body = response.body ?: return ""
        val bytes = body.bytes()
        if (bytes.isEmpty()) {
            return ""
        }
        val declaredCharset = body.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8
        val declaredText = String(bytes, declaredCharset)
        val gbText = runCatching { String(bytes, Charset.forName("GB18030")) }.getOrNull()
            ?: return declaredText
        return if (isBetterDecodedText(gbText, declaredText)) gbText else declaredText
    }

    private fun isBetterDecodedText(candidate: String, current: String): Boolean {
        val candidateCjk = candidate.count { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN }
        val currentCjk = current.count { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN }
        val candidateBroken = candidate.count { it == '\uFFFD' || it == '?' }
        val currentBroken = current.count { it == '\uFFFD' || it == '?' }
        return candidateCjk > currentCjk && candidateBroken <= currentBroken
    }

    private fun updateCookies(response: Response, cookies: MutableMap<String, String>) {
        response.headers("Set-Cookie").forEach { header ->
            val pair = header.substringBefore(';')
            val name = pair.substringBefore('=', "")
            val value = pair.substringAfter('=', "")
            if (name.isNotBlank()) {
                cookies[name] = value
            }
        }
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://m407.mos077.com"
    }

    private data class CrownGameDetailKey(
        val lid: String,
        val detailId: String,
        val isLive: Boolean
    )
}
