package com.wrbug.polymarketbot.service.oddsmonitor.collector.crown

import com.wrbug.polymarketbot.entity.OddsDataSourceConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import com.sun.net.httpserver.HttpServer

class CrownApiClientFetchControlTest {
    private val config = OddsDataSourceConfig(
        sourceKey = "crown",
        displayName = "Crown",
        enabled = true,
        queryKeyword = "https://crown.example"
    )
    private val session = CrownSession(
        uid = "uid-1",
        cookies = emptyMap(),
        username = "alice",
        baseUrl = "https://crown.example"
    )

    @Test
    fun `deduplicates crown detail requests from repeated game list items`() {
        val client = FakeCrownApiClient(
            responses = ArrayDeque(
                listOf(
                    gameListResponse(
                        item("900", "EC-1", "N"),
                        item("900", "EC-1", "N")
                    ),
                    detailResponse("41001")
                )
            )
        )

        val result = client.fetchMatchesWithSession(config, session)

        assertEquals(1, result.matches.size)
        assertEquals(1, client.detailRequestCount)
    }

    @Test
    fun `stops crown detail requests when collection budget is exhausted`() {
        var now = 0L
        val client = FakeCrownApiClient(
            fetchTimeoutMillis = 50,
            nowMillis = { now },
            onDetailRequest = { now += 60 },
            responses = ArrayDeque(
                listOf(
                    gameListResponse(
                        item("900", "EC-1", "N"),
                        item("901", "EC-2", "N")
                    ),
                    detailResponse("41001")
                )
            )
        )

        val exception = assertThrows(CrownCollectionException::class.java) {
            client.fetchMatchesWithSession(config, session)
        }

        assertEquals("failed_timeout", exception.status)
        assertEquals(1, client.detailRequestCount)
    }

    @Test
    fun `retries crown request after transient socket timeout`() {
        val attempts = AtomicInteger(0)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val executor = Executors.newCachedThreadPool()
        server.executor = executor
        server.createContext("/transform.php") { exchange ->
            if (attempts.incrementAndGet() == 1) {
                Thread.sleep(1_200)
            }
            val response = detailResponse("41001").toByteArray(Charsets.UTF_8)
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        server.start()
        try {
            val baseUrl = "http://127.0.0.1:${server.address.port}"
            val client = CrownApiClient(
                parser = CrownResponseParser(),
                fetchTimeoutMillis = 5_000,
                httpCallTimeoutSeconds = 1,
                nowMillis = System::currentTimeMillis
            )

            val result = client.fetchMatchesWithSession(
                config.copy(queryKeyword = baseUrl),
                session.copy(baseUrl = baseUrl)
            )

            assertEquals(1, result.matches.size)
            assertEquals(2, attempts.get())
        } finally {
            server.stop(0)
            executor.shutdownNow()
        }
    }

    private class FakeCrownApiClient(
        fetchTimeoutMillis: Long = 55_000,
        nowMillis: () -> Long = { 0L },
        private val onDetailRequest: () -> Unit = {},
        private val responses: ArrayDeque<String>
    ) : CrownApiClient(
        parser = CrownResponseParser(),
        fetchTimeoutMillis = fetchTimeoutMillis,
        httpCallTimeoutSeconds = 1,
        nowMillis = nowMillis
    ) {
        var detailRequestCount = 0

        override fun postForm(
            baseUrl: String,
            path: String,
            form: Map<String, String>,
            cookies: MutableMap<String, String>
        ): String {
            if (form["p"] == "get_game_more") {
                detailRequestCount += 1
                onDetailRequest()
            }
            return responses.removeFirst()
        }
    }

    private fun item(lid: String, detailId: String, isRb: String): String {
        return """
            <item>
              <lid>$lid</lid>
              <gidm>$detailId</gidm>
              <ecid>$detailId</ecid>
              <retimeset>0</retimeset>
              <is_rb>$isRb</is_rb>
            </item>
        """.trimIndent()
    }

    private fun gameListResponse(vararg items: String): String {
        return """
            <serverresponse>
              <ec>
                ${items.joinToString("\n")}
              </ec>
            </serverresponse>
        """.trimIndent()
    }

    private fun detailResponse(gid: String): String {
        return """
            <serverresponse>
              <game>
                <gid>$gid</gid>
                <league>England Premier League</league>
                <datetime>05-01 19:30</datetime>
                <team_h>Arsenal</team_h>
                <team_c>Chelsea</team_c>
                <ratio>0 / 0.5</ratio>
                <ior_rh>1.93</ior_rh>
                <ior_rc>1.97</ior_rc>
              </game>
            </serverresponse>
        """.trimIndent()
    }
}
