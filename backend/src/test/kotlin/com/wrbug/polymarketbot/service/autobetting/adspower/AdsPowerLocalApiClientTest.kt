package com.wrbug.polymarketbot.service.autobetting.adspower

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

class AdsPowerLocalApiClientTest {
    @Test
    fun `status builds local api request and maps success`() {
        TestAdsPowerServer().use { server ->
            server.onGet("/status") { exchange ->
                exchange.respondJson("""{"code":0,"msg":"success"}""")
            }
            val client = AdsPowerLocalApiClient(
                objectMapper = jacksonObjectMapper(),
                baseUrl = server.baseUrl,
                apiKey = "secret-token"
            )

            val status = client.checkStatus(now = 1234)

            assertTrue(status.available)
            assertEquals(server.baseUrl, status.baseUrl)
            assertEquals(0, status.code)
            assertEquals("success", status.message)
            assertEquals("Bearer secret-token", server.lastAuthorizationHeader)
        }
    }

    @Test
    fun `start profile builds browser start query and maps failed response`() {
        TestAdsPowerServer().use { server ->
            server.onGet("/api/v1/browser/start") { exchange ->
                val query = exchange.queryParams()
                assertEquals("profile-001", query["user_id"])
                assertEquals("1", query["open_tabs"])
                assertEquals("1", query["ip_tab"])
                assertEquals("0", query["headless"])
                exchange.respondJson("""{"code":-1,"msg":"profile_not_found","data":{}}""")
            }
            val client = AdsPowerLocalApiClient(
                objectMapper = jacksonObjectMapper(),
                baseUrl = server.baseUrl,
                apiKey = null
            )

            val result = client.startProfile(profileId = " profile-001 ", now = 3456)

            assertFalse(result.opened)
            assertEquals("profile-001", result.profileId)
            assertEquals("profile_not_found", result.message)
            assertEquals(3456, result.openedAt)
        }
    }

    private class TestAdsPowerServer : AutoCloseable {
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        private val handlers = mutableMapOf<String, (HttpExchange) -> Unit>()

        val baseUrl: String = "http://127.0.0.1:${server.address.port}"
        var lastAuthorizationHeader: String? = null
            private set

        init {
            server.createContext("/") { exchange ->
                lastAuthorizationHeader = exchange.requestHeaders.getFirst("Authorization")
                handlers[exchange.requestURI.path]?.invoke(exchange)
                    ?: exchange.sendResponseHeaders(404, 0)
                exchange.close()
            }
            server.start()
        }

        fun onGet(path: String, handler: (HttpExchange) -> Unit) {
            handlers[path] = { exchange ->
                assertEquals("GET", exchange.requestMethod)
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
    return requestURI.rawQuery
        ?.split("&")
        ?.filter { it.isNotBlank() }
        ?.associate { part ->
            val key = part.substringBefore("=")
            val value = part.substringAfter("=", "")
            URLDecoder.decode(key, StandardCharsets.UTF_8) to URLDecoder.decode(value, StandardCharsets.UTF_8)
        }
        .orEmpty()
}
