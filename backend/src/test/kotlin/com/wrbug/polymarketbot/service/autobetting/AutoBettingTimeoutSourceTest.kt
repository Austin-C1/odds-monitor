package com.wrbug.polymarketbot.service.autobetting

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class AutoBettingTimeoutSourceTest {
    @Test
    fun `placing intents are considered stale after the thirty second account execution limit`() {
        val executionSource = Files.readString(
            Path.of("src/main/kotlin/com/wrbug/polymarketbot/service/autobetting/AutoBettingExecutionService.kt")
        )
        val decisionSource = Files.readString(
            Path.of("src/main/kotlin/com/wrbug/polymarketbot/service/autobetting/AutoBettingDecisionService.kt")
        )
        val migrationSource = Files.readString(
            Path.of("src/main/resources/db/migration/V65__release_stale_auto_betting_placing_30s.sql")
        )

        assertTrue(executionSource.contains("stalePlacingTimeoutMillis = 30_000L"))
        assertTrue(decisionSource.contains("stalePlacingTimeoutMillis = 30_000L"))
        assertTrue(migrationSource.contains("status = 'placing'"))
        assertTrue(migrationSource.contains("- 30000"))
    }
}
