package com.wrbug.polymarketbot.service.copytrading.monitor

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class PolymarketActivityWsServiceImplementationTest {

    private val sourcePath: Path = Path.of(
        "src",
        "main",
        "kotlin",
        "com",
        "wrbug",
        "polymarketbot",
        "service",
        "copytrading",
        "monitor",
        "PolymarketActivityWsService.kt"
    )

    @Test
    fun `activity websocket counters should use atomic counters`() {
        val source = Files.readString(sourcePath)

        assertTrue(
            source.contains("AtomicLong"),
            "Activity websocket counters should be backed by AtomicLong"
        )
    }

    @Test
    fun `activity timeout monitor should not stop itself after a reconnect attempt`() {
        val source = Files.readString(sourcePath)

        assertFalse(
            source.contains("connectAndSubscribe()\n                break"),
            "Timeout monitor should keep running after reconnect attempts"
        )
        assertFalse(
            source.contains("if (!isSubscribed) {\n                    break"),
            "Timeout monitor should not exit permanently when the socket is temporarily unsubscribed"
        )
    }
}
