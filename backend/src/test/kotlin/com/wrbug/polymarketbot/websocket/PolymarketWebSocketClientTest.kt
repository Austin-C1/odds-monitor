package com.wrbug.polymarketbot.websocket

import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.reflect.full.memberProperties
import kotlin.reflect.jvm.isAccessible

class PolymarketWebSocketClientTest {

    @Test
    fun `closeConnection shuts down okhttp resources`() {
        val client = PolymarketWebSocketClient(
            url = "wss://example.com/ws",
            sessionId = "session-1",
            onMessage = {}
        )

        val okHttpClient = client::class.memberProperties
            .first { it.name == "okHttpClient" }
            .apply { isAccessible = true }
            .getter
            .call(client) as OkHttpClient

        assertFalse(okHttpClient.dispatcher.executorService.isShutdown)

        client.closeConnection()

        assertTrue(okHttpClient.dispatcher.executorService.isShutdown)
    }
}
