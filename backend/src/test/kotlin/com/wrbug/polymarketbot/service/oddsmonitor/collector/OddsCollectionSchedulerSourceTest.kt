package com.wrbug.polymarketbot.service.oddsmonitor.collector

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class OddsCollectionSchedulerSourceTest {
    @Test
    fun `crown collection scheduler polls every second to keep sixty second intervals tight`() {
        val crownSource = Files.readString(
            Path.of("src/main/kotlin/com/wrbug/polymarketbot/service/oddsmonitor/collector/crown/CrownCollectionScheduler.kt")
        )

        assertTrue(crownSource.contains("@Scheduled(fixedDelay = 1_000)"))
    }
}
