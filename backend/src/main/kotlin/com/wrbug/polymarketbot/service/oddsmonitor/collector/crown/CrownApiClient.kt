package com.wrbug.polymarketbot.service.oddsmonitor.collector.crown

import com.wrbug.polymarketbot.entity.OddsDataSourceConfig
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.springframework.stereotype.Component
import java.nio.charset.Charset
import java.util.concurrent.TimeUnit

@Component
class CrownApiClient(
    private val parser: CrownResponseParser
) {
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    fun login(config: OddsDataSourceConfig): CrownSession {
        val username = config.username?.takeIf { it.isNotBlank() }
            ?: throw CrownCollectionException("failed_config", "crown username is empty")
        val password = config.password?.takeIf { it.isNotBlank() }
            ?: throw CrownCollectionException("failed_config", "crown password is empty")

        val cookieJar = linkedMapOf<String, String>()
        val response = postForm(
            baseUrl = config.crownBaseUrl(),
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
        return CrownSession(uid = login.uid, cookies = cookieJar.toMap())
    }

    fun fetchMatches(config: OddsDataSourceConfig, session: CrownSession): List<CrownFootballMatch> {
        val baseUrl = config.crownBaseUrl()
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

        val directMatches = parser.parseDetailGames(listResponse, isLive = false)
        if (directMatches.isNotEmpty()) {
            return directMatches
        }

        return parser.parseGameList(listResponse).flatMap { item ->
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
            parser.parseDetailGames(detailResponse, item.isLive)
        }
    }

    private fun postForm(
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

    private fun OddsDataSourceConfig.crownBaseUrl(): String {
        val configured = queryKeyword?.trim()?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
        return configured ?: DEFAULT_BASE_URL
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://m407.mos077.com"
    }
}
