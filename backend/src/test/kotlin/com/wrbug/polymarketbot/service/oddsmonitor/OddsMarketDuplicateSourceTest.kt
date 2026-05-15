package com.wrbug.polymarketbot.service.oddsmonitor

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OddsMarketDuplicateSourceTest {

    @Test
    fun `market lookups should tolerate duplicate rows by reading newest row`() {
        val repositorySource = Files.readString(
            Path.of("src/main/kotlin/com/wrbug/polymarketbot/repository/OddsMonitorRepositories.kt")
        )
        val productionSources = listOf(
            "src/main/kotlin/com/wrbug/polymarketbot/service/oddsmonitor/collector/crown/CrownCollector.kt",
            "src/main/kotlin/com/wrbug/polymarketbot/service/oddsmonitor/collector/pinnacle/PinnacleCollector.kt",
            "src/main/kotlin/com/wrbug/polymarketbot/service/oddsmonitor/OddsChangeNotificationService.kt"
        ).associateWith { path -> Files.readString(Path.of(path)) }

        assertTrue(
            repositorySource.contains("findTopByMatchIdAndSourceKeyAndMarketTypeAndLineValueAndSelectionNameOrderByUpdatedAtDesc"),
            "OddsMarketRepository should expose a newest-row lookup so duplicated odds_markets rows do not crash collectors"
        )
        productionSources.forEach { (path, source) ->
            assertTrue(
                source.contains("findTopByMatchIdAndSourceKeyAndMarketTypeAndLineValueAndSelectionNameOrderByUpdatedAtDesc"),
                "$path should use the duplicate-tolerant market lookup"
            )
            assertFalse(
                source.contains("findByMatchIdAndSourceKeyAndMarketTypeAndLineValueAndSelectionName("),
                "$path should not use the single-result lookup that throws when duplicate rows exist"
            )
        }
    }
}
