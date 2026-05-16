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
