package com.wrbug.polymarketbot.oddsmonitor

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class OddsMonitorFoundationTest {
    @Test
    fun `migration creates odds monitoring foundation tables`() {
        val sql = Files.readString(Path.of("src/main/resources/db/migration/V53__create_odds_monitor_foundation.sql"))

        listOf(
            "odds_matches",
            "odds_platform_matches",
            "odds_match_links",
            "odds_markets",
            "odds_snapshots",
            "odds_alert_records",
            "odds_collection_logs",
            "odds_data_source_configs"
        ).forEach { table ->
            assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS $table"), "missing table $table")
        }
    }

    @Test
    fun `pinnacle and crown collectors have reserved modules`() {
        assertTrue(Files.exists(Path.of("src/main/kotlin/com/wrbug/polymarketbot/service/oddsmonitor/collector/pinnacle/PinnacleCollector.kt")))
        assertTrue(Files.exists(Path.of("src/main/kotlin/com/wrbug/polymarketbot/service/oddsmonitor/collector/crown/CrownCollector.kt")))
    }

    @Test
    fun `active odds monitor backend text is readable Chinese`() {
        val files = listOf(
            "src/main/kotlin/com/wrbug/polymarketbot/service/oddsmonitor/OddsMonitorService.kt",
            "src/main/kotlin/com/wrbug/polymarketbot/controller/auth/AuthController.kt",
            "src/main/kotlin/com/wrbug/polymarketbot/config/JwtAuthenticationInterceptor.kt",
            "src/main/kotlin/com/wrbug/polymarketbot/enums/ErrorCode.kt"
        )
        val combined = files.joinToString("\n") { Files.readString(Path.of(it)) }
        val markers = listOf("骞", "鐨", "鑻", "妯", "璁", "澶", "瑙", "鐧", "瀵", "鏌", "绁", "楠", "鍑", "閿")

        markers.forEach { marker ->
            assertFalse(combined.contains(marker), "mojibake marker remains: $marker")
        }

        assertTrue(combined.contains("平博"))
        assertTrue(combined.contains("皇冠"))
        assertTrue(combined.contains("免密登录失败"))
        assertTrue(combined.contains("认证令牌无效或已过期"))
    }
}
