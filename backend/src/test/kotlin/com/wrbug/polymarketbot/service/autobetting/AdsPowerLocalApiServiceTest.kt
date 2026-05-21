package com.wrbug.polymarketbot.service.autobetting

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

class AdsPowerLocalApiServiceTest {

    @Test
    fun `status reports available when local api returns success`() {
        TestAdsPowerServer().use { server ->
            server.onGet("/status") { exchange ->
                exchange.respondJson("""{"code":0,"msg":"success"}""")
            }
            val service = AdsPowerLocalApiService(
                objectMapper = jacksonObjectMapper(),
                baseUrl = server.baseUrl,
                apiKey = "secret-token"
            )

            val status = service.checkStatus(now = 1234)

            assertTrue(status.available)
            assertEquals(server.baseUrl, status.baseUrl)
            assertEquals(0, status.code)
            assertEquals("success", status.message)
            assertEquals(1234, status.checkedAt)
            assertEquals("Bearer secret-token", server.lastAuthorizationHeader)
        }
    }

    @Test
    fun `status reports unavailable when local api cannot be reached`() {
        val service = AdsPowerLocalApiService(
            objectMapper = jacksonObjectMapper(),
            baseUrl = "http://127.0.0.1:9",
            apiKey = ""
        )

        val status = service.checkStatus(now = 2233)

        assertFalse(status.available)
        assertEquals(2233, status.checkedAt)
        assertNull(status.code)
    }

    @Test
    fun `status rejects non-local api base url`() {
        val service = AdsPowerLocalApiService(
            objectMapper = jacksonObjectMapper(),
            baseUrl = "http://example.com:50325",
            apiKey = ""
        )

        val status = service.checkStatus(now = 2234)

        assertFalse(status.available)
        assertEquals("invalid_adspower_base_url", status.message)
        assertEquals(2234, status.checkedAt)
    }

    @Test
    fun `start profile opens adspower browser and returns debug port`() {
        TestAdsPowerServer().use { server ->
            server.onGet("/api/v1/browser/start") { exchange ->
                val query = exchange.queryParams()
                assertEquals("profile-001", query["user_id"])
                assertEquals("1", query["open_tabs"])
                assertEquals("1", query["ip_tab"])
                assertEquals("0", query["headless"])
                exchange.respondJson(
                    """
                    {
                      "code": 0,
                      "msg": "success",
                      "data": {
                        "ws": {
                          "selenium": "127.0.0.1:39555",
                          "puppeteer": "ws://127.0.0.1:39555/devtools/browser/abc"
                        },
                        "debug_port": "39555",
                        "webdriver": "C:\\AdsPower\\chromedriver.exe"
                      }
                    }
                    """.trimIndent()
                )
            }
            val service = AdsPowerLocalApiService(
                objectMapper = jacksonObjectMapper(),
                baseUrl = server.baseUrl,
                apiKey = null
            )

            val result = service.startProfile(profileId = " profile-001 ", now = 3456)

            assertTrue(result.opened)
            assertEquals("profile-001", result.profileId)
            assertEquals("success", result.message)
            assertEquals("39555", result.debugPort)
            assertEquals(3456, result.openedAt)
        }
    }

    @Test
    fun `start profile accepts visible AdsPower serial number`() {
        TestAdsPowerServer().use { server ->
            server.onGet("/api/v1/browser/start") { exchange ->
                val query = exchange.queryParams()
                if (query["user_id"] == "27") {
                    exchange.respondJson("""{"code":-1,"msg":"failed","data":{}}""")
                    return@onGet
                }
                assertEquals("27", query["serial_number"])
                exchange.respondJson(
                    """
                    {
                      "code": 0,
                      "msg": "success",
                      "data": {
                        "ws": {
                          "selenium": "127.0.0.1:39555"
                        }
                      }
                    }
                    """.trimIndent()
                )
            }
            val service = AdsPowerLocalApiService(
                objectMapper = jacksonObjectMapper(),
                baseUrl = server.baseUrl,
                apiKey = null
            )

            val result = service.startProfile(profileId = "27", now = 3460)

            assertTrue(result.opened)
            assertEquals("27", result.profileId)
            assertEquals("39555", result.debugPort)
            assertEquals(2, server.requestCount)
        }
    }

    @Test
    fun `active profile check reports opened browser and returns debug port`() {
        TestAdsPowerServer().use { server ->
            server.onGet("/api/v1/browser/active") { exchange ->
                val query = exchange.queryParams()
                assertEquals("profile-001", query["user_id"])
                exchange.respondJson(
                    """
                    {
                      "code": 0,
                      "msg": "success",
                      "data": {
                        "status": "Active",
                        "debug_port": "39555"
                      }
                    }
                    """.trimIndent()
                )
            }
            val service = AdsPowerLocalApiService(
                objectMapper = jacksonObjectMapper(),
                baseUrl = server.baseUrl,
                apiKey = "secret-token"
            )

            val result = service.checkProfileActive(profileId = " profile-001 ", now = 5678)

            assertTrue(result.opened)
            assertEquals("profile-001", result.profileId)
            assertEquals("success", result.message)
            assertEquals("39555", result.debugPort)
            assertEquals(5678, result.checkedAt)
            assertEquals("Bearer secret-token", server.lastAuthorizationHeader)
        }
    }

    @Test
    fun `active profile check accepts visible AdsPower serial number`() {
        TestAdsPowerServer().use { server ->
            server.onGet("/api/v1/browser/active") { exchange ->
                val query = exchange.queryParams()
                if (query["user_id"] == "27") {
                    exchange.respondJson("""{"code":-1,"msg":"failed","data":{}}""")
                    return@onGet
                }
                assertEquals("27", query["serial_number"])
                exchange.respondJson(
                    """
                    {
                      "code": 0,
                      "msg": "success",
                      "data": {
                        "status": "Active",
                        "ws": {
                          "selenium": "127.0.0.1:39555"
                        }
                      }
                    }
                    """.trimIndent()
                )
            }
            val service = AdsPowerLocalApiService(
                objectMapper = jacksonObjectMapper(),
                baseUrl = server.baseUrl,
                apiKey = null
            )

            val result = service.checkProfileActive(profileId = "27", now = 5679)

            assertTrue(result.opened)
            assertEquals("27", result.profileId)
            assertEquals("39555", result.debugPort)
            assertEquals(2, server.requestCount)
        }
    }

    @Test
    fun `active profile check reports closed browser`() {
        TestAdsPowerServer().use { server ->
            server.onGet("/api/v1/browser/active") { exchange ->
                exchange.respondJson(
                    """
                    {
                      "code": 0,
                      "msg": "success",
                      "data": {
                        "status": "Inactive"
                      }
                    }
                    """.trimIndent()
                )
            }
            val service = AdsPowerLocalApiService(
                objectMapper = jacksonObjectMapper(),
                baseUrl = server.baseUrl,
                apiKey = null
            )

            val result = service.checkProfileActive(profileId = "profile-002", now = 6789)

            assertFalse(result.opened)
            assertEquals("profile-002", result.profileId)
            assertEquals("success", result.message)
            assertEquals(6789, result.checkedAt)
        }
    }

    @Test
    fun `match crown session falls back to named profile when local active list is empty`() {
        TestAdsPowerServer().use { server ->
            server.onGet("/api/v1/browser/local-active") { exchange ->
                exchange.respondJson("""{"code":0,"msg":"success","data":{"list":[]}}""")
            }
            server.onGet("/api/v1/user/list") { exchange ->
                val query = exchange.queryParams()
                if (query["page_size"] == "100") {
                    exchange.respondJson(
                        """
                        {
                          "code": 0,
                          "msg": "success",
                          "data": {
                            "list": [
                              {
                                "serial_number": "27",
                                "user_id": "real-profile-id",
                                "name": "skjd447",
                                "username": "",
                                "remark": ""
                              }
                            ]
                          }
                        }
                        """.trimIndent()
                    )
                    return@onGet
                }
                exchange.respondJson("""{"code":0,"msg":"success","data":{"list":[]}}""")
            }
            server.onGet("/api/v1/browser/active") { exchange ->
                val query = exchange.queryParams()
                assertEquals("real-profile-id", query["user_id"])
                exchange.respondJson(
                    """
                    {
                      "code": 0,
                      "msg": "success",
                      "data": {
                        "status": "Active",
                        "debug_port": "39556"
                      }
                    }
                    """.trimIndent()
                )
            }
            val service = AdsPowerLocalApiService(
                objectMapper = jacksonObjectMapper(),
                baseUrl = server.baseUrl,
                apiKey = null
            )

            val result = service.matchCrownSession(loginName = "skjd447", loginUrl = "https://m407.mos077.com/", now = 6780)

            assertEquals("real-profile-id", result.profileId)
            assertTrue(result.opened)
            assertEquals("crown_page_not_found", result.accountStatus)
        }
    }

    @Test
    fun `match crown session keeps opened state when active browser has no readable crown login`() {
        TestAdsPowerServer().use { server ->
            val debugPort = server.baseUrl.substringAfterLast(":")
            server.onGet("/api/v1/browser/local-active") { exchange ->
                exchange.respondJson(
                    """
                    {
                      "code": 0,
                      "msg": "success",
                      "data": {
                        "list": [
                          {
                            "user_id": "profile-open",
                            "debug_port": "$debugPort"
                          }
                        ]
                      }
                    }
                    """.trimIndent()
                )
            }
            server.onGet("/api/v1/user/list") { exchange ->
                exchange.respondJson(
                    """
                    {
                      "code": 0,
                      "msg": "success",
                      "data": {
                        "list": [
                          {
                            "user_id": "profile-open",
                            "name": "cuu0i93ltuo",
                            "username": "cuu0i93ltuo",
                            "remark": ""
                          }
                        ]
                      }
                    }
                    """.trimIndent()
                )
            }
            server.onGet("/json/list") { exchange ->
                exchange.respondJson("""[]""")
            }
            val service = AdsPowerLocalApiService(
                objectMapper = jacksonObjectMapper(),
                baseUrl = server.baseUrl,
                apiKey = null
            )

            val result = service.matchCrownSession(loginName = "cuu0i93ltuo", loginUrl = "https://m407.mos077.com/", now = 6790)

            assertTrue(result.opened)
            assertFalse(result.loggedIn)
            assertEquals("no_logged_in_crown_profile", result.accountStatus)
        }
    }

    @Test
    fun `crown session analyzer reports online account and balance`() {
        val result = CrownSessionPageAnalyzer.analyze(
            """
            会员中心
            账户余额：RMB 2,000.00
            退出
            """.trimIndent()
        )

        assertTrue(result.loggedIn)
        assertEquals("online", result.accountStatus)
        assertEquals(BigDecimal("2000.00"), result.balance)
        assertEquals("账号在线，余额已获取", result.message)
    }

    @Test
    fun `crown session analyzer reads compact web header balance`() {
        val result = CrownSessionPageAnalyzer.analyze(
            """
            skjd447RMB2,000.00
            账户历史
            讯息
            投注记录
            """.trimIndent()
        )

        assertTrue(result.loggedIn)
        assertEquals("online", result.accountStatus)
        assertEquals(BigDecimal("2000.00"), result.balance)
        assertEquals("skjd447", result.loginName)
    }

    @Test
    fun `crown session analyzer prefers visible account header over stale page title`() {
        val result = CrownSessionPageAnalyzer.analyze(
            text = """
            skjd447RMB2,000.00
            STATEMENT
            MESSAGES
            """.trimIndent(),
            pageTitle = "cs1"
        )

        assertTrue(result.loggedIn)
        assertEquals("online", result.accountStatus)
        assertEquals(BigDecimal("2000.00"), result.balance)
        assertEquals("skjd447", result.loginName)
    }

    @Test
    fun `crown session analyzer reports online from logged in web menu without balance`() {
        val result = CrownSessionPageAnalyzer.analyze(
            """
            skjd447
            账户历史
            讯息
            设置
            账户安全
            修改密码
            投注记录
            """.trimIndent()
        )

        assertTrue(result.loggedIn)
        assertEquals("online", result.accountStatus)
        assertNull(result.balance)
        assertEquals("skjd447", result.loginName)
        assertEquals("账号在线，余额未读取到", result.message)
    }

    @Test
    fun `crown session analyzer does not read sport counts as login name`() {
        val result = CrownSessionPageAnalyzer.analyze(
            """
            滚球 27
            今日 333
            早盘 298
            账户历史
            讯息
            修改密码
            """.trimIndent()
        )

        assertTrue(result.loggedIn)
        assertEquals("online", result.accountStatus)
        assertNull(result.loginName)
    }

    @Test
    fun `crown session analyzer does not read timezone or navigation labels as login name`() {
        val result = CrownSessionPageAnalyzer.analyze(
            """
            In-Play
            Hot
            Today
            Soon
            Early
            Outrights
            My Bets
            RMB
            2,000.00
            EVENT DISPLAY TIME
            System Time (GMT-4)
            PHONE
            +852 5808 9063
            """.trimIndent()
        )

        assertTrue(result.loggedIn)
        assertEquals("online", result.accountStatus)
        assertEquals(BigDecimal("2000.00"), result.balance)
        assertNull(result.loginName)
    }

    @Test
    fun `crown session analyzer reads account from hidden crown page signals`() {
        val result = CrownSessionPageAnalyzer.analyze(
            text = """
            In-Play
            Hot
            Today
            My Bets
            RMB
            2,000.00
            username: cuu0mdl1lac
            balance: RMB 2,000.00
            """.trimIndent(),
            pageTitle = "Welcome"
        )

        assertTrue(result.loggedIn)
        assertEquals("online", result.accountStatus)
        assertEquals(BigDecimal("2000.00"), result.balance)
        assertEquals("cuu0mdl1lac", result.loginName)
    }

    @Test
    fun `crown session analyzer reads login name from crown browser tab title`() {
        val result = CrownSessionPageAnalyzer.analyze(
            text = """
            In-Play
            Hot
            Today
            RMB
            2,000.00
            EVENT DISPLAY TIME
            System Time (GMT-4)
            """.trimIndent(),
            pageTitle = "skjd447"
        )

        assertTrue(result.loggedIn)
        assertEquals("online", result.accountStatus)
        assertEquals(BigDecimal("2000.00"), result.balance)
        assertEquals("skjd447", result.loginName)
    }

    @Test
    fun `crown session analyzer reports login required when login form is visible`() {
        val result = CrownSessionPageAnalyzer.analyze("皇冠 登录 账号 密码 Login")

        assertFalse(result.loggedIn)
        assertEquals("login_required", result.accountStatus)
        assertNull(result.balance)
    }

    @Test
    fun `blank profile id is rejected before calling local api`() {
        TestAdsPowerServer().use { server ->
            val service = AdsPowerLocalApiService(
                objectMapper = jacksonObjectMapper(),
                baseUrl = server.baseUrl,
                apiKey = null
            )

            val result = service.startProfile(profileId = "   ", now = 4567)

            assertFalse(result.opened)
            assertEquals("", result.profileId)
            assertEquals("profile_id_required", result.message)
            assertEquals(0, server.requestCount)
        }
    }

    private class TestAdsPowerServer : AutoCloseable {
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val baseUrl: String = "http://127.0.0.1:${server.address.port}"
        var requestCount: Int = 0
            private set
        var lastAuthorizationHeader: String? = null
            private set

        init {
            server.start()
        }

        fun onGet(path: String, handler: (HttpExchange) -> Unit) {
            server.createContext(path) { exchange ->
                requestCount += 1
                lastAuthorizationHeader = exchange.requestHeaders.getFirst("Authorization")
                if (exchange.requestMethod != "GET") {
                    exchange.sendResponseHeaders(405, -1)
                    exchange.close()
                    return@createContext
                }
                handler(exchange)
            }
        }

        override fun close() {
            server.stop(0)
        }
    }
}

private fun HttpExchange.respondJson(body: String) {
    val bytes = body.toByteArray(StandardCharsets.UTF_8)
    responseHeaders.add("Content-Type", "application/json; charset=utf-8")
    sendResponseHeaders(200, bytes.size.toLong())
    responseBody.use { it.write(bytes) }
}

private fun HttpExchange.queryParams(): Map<String, String> {
    val rawQuery = requestURI.rawQuery ?: return emptyMap()
    return rawQuery.split("&")
        .filter { it.isNotBlank() }
        .associate { pair ->
            val parts = pair.split("=", limit = 2)
            val key = URLDecoder.decode(parts[0], StandardCharsets.UTF_8)
            val value = URLDecoder.decode(parts.getOrElse(1) { "" }, StandardCharsets.UTF_8)
            key to value
        }
}
