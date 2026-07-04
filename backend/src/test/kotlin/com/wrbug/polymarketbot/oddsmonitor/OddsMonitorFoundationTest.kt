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
    fun `crown collector is the only active odds collector module`() {
        assertFalse(Files.exists(Path.of("src/main/kotlin/com/wrbug/polymarketbot/service/oddsmonitor/collector/pinnacle")))
        assertTrue(Files.exists(Path.of("src/main/kotlin/com/wrbug/polymarketbot/service/oddsmonitor/collector/crown/CrownCollector.kt")))
    }

    @Test
    fun `pinnacle browser runtime dependencies are removed`() {
        val build = Files.readString(Path.of("build.gradle.kts"))
        val properties = Files.readString(Path.of("src/main/resources/application.properties"))

        assertFalse(build.contains("org.jsoup:jsoup"))
        assertFalse(build.contains("com.microsoft.playwright:playwright"))
        assertFalse(properties.contains("pinnacle.browser"))
    }

    @Test
    fun `active backend runtime no longer exposes pinnacle source`() {
        val runtimeFiles = listOf(
            "src/main/kotlin/com/wrbug/polymarketbot/dto/OddsMonitorDto.kt",
            "src/main/kotlin/com/wrbug/polymarketbot/service/oddsmonitor/OddsMonitorService.kt",
            "src/main/kotlin/com/wrbug/polymarketbot/service/oddsmonitor/OddsLeagueFilterService.kt",
            "src/main/kotlin/com/wrbug/polymarketbot/service/oddsmonitor/OddsChangeNotificationService.kt",
            "src/main/kotlin/com/wrbug/polymarketbot/service/oddsmonitor/OddsStandardMatchService.kt",
            "src/main/kotlin/com/wrbug/polymarketbot/service/autobetting/AutoBettingDecisionService.kt",
            "src/main/kotlin/com/wrbug/polymarketbot/service/system/NotificationTemplateService.kt"
        )

        runtimeFiles.forEach { file ->
            val source = Files.readString(Path.of(file))
            listOf("pinnacle", "Pinnacle", "平博", "骞冲崥").forEach { forbidden ->
                assertFalse(source.contains(forbidden), "$file still exposes $forbidden")
            }
        }
    }

    @Test
    fun `active odds monitor backend text is readable Chinese`() {
        val files = listOf(
            "src/main/kotlin/com/wrbug/polymarketbot/service/oddsmonitor/OddsMonitorService.kt",
            "src/main/kotlin/com/wrbug/polymarketbot/service/oddsmonitor/OddsDataSourceService.kt",
            "src/main/kotlin/com/wrbug/polymarketbot/controller/auth/AuthController.kt",
            "src/main/kotlin/com/wrbug/polymarketbot/config/JwtAuthenticationInterceptor.kt",
            "src/main/kotlin/com/wrbug/polymarketbot/enums/ErrorCode.kt"
        )
        val combined = files.joinToString("\n") { Files.readString(Path.of(it)) }
        val markers = listOf(
            "\u9A9E", "\u9428", "\u947B", "\u59AF", "\u7481", "\u6FB6", "\u7459",
            "\u9427", "\u7035", "\u93CC", "\u7EC1", "\u6960", "\u9351", "\u95BF"
        )

        markers.forEach { marker ->
            assertFalse(combined.contains(marker), "mojibake marker remains: $marker")
        }

        assertTrue(combined.contains("皇冠"))
        assertTrue(combined.contains("免密登录失败"))
        assertTrue(combined.contains("认证令牌无效或已过期"))
    }

    @Test
    fun `default league migration text is readable Chinese`() {
        val sql = Files.readString(Path.of("src/main/resources/db/migration/V57__set_default_league_filters.sql"))
        val markers = listOf(
            "\u9A9E", "\u9428", "\u947B", "\u59AF", "\u7481", "\u6FB6", "\u7459",
            "\u9427", "\u7035", "\u93CC", "\u7EC1", "\u6960", "\u9351", "\u95BF"
        )

        markers.forEach { marker ->
            assertFalse(sql.contains(marker), "mojibake marker remains: $marker")
        }

        assertTrue(sql.contains("芬兰 - 全国联赛"))
        assertTrue(sql.contains("芬兰甲组联赛"))
        assertTrue(sql.contains("全平台赔率监控皇冠默认比赛清单"))
        assertTrue(sql.contains("美国公开赛冠军杯"))
    }
    @Test
    fun `unconfigured crown source cleanup is preserved in the latest migration`() {
        assertFalse(
            Files.exists(Path.of("src/main/resources/db/migration/V67__clear_unconfigured_crown_default_mirror.sql")),
            "local databases already applied a different V67, so this source migration must not be kept"
        )

        val sql = Files.readString(Path.of("src/main/resources/db/migration/V73__remove_pinnacle_runtime_data.sql"))

        assertTrue(sql.contains("source_key = 'crown'"))
        assertTrue(sql.contains("query_keyword = NULL"))
        assertTrue(sql.contains("username IS NULL"))
        assertTrue(sql.contains("password IS NULL"))
    }

    @Test
    fun `pinnacle removal migration physically deletes runtime data`() {
        val sql = Files.readString(Path.of("src/main/resources/db/migration/V73__remove_pinnacle_runtime_data.sql"))

        listOf(
            "DELETE FROM odds_snapshots",
            "DELETE FROM odds_markets",
            "DELETE FROM odds_match_links",
            "DELETE FROM odds_platform_matches",
            "DELETE FROM odds_collection_logs",
            "DELETE FROM odds_alert_records",
            "DELETE FROM odds_data_source_configs",
            "DELETE FROM system_config",
            "DELETE FROM auto_betting_intents",
            "source_key = 'pinnacle'",
            "reference_source_key = 'pinnacle'",
            "target_source_key = 'pinnacle'",
            "odds_monitor.selected_leagues.pinnacle"
        ).forEach { expected ->
            assertTrue(sql.contains(expected), "missing migration statement: $expected")
        }

        assertTrue(sql.indexOf("DELETE FROM odds_snapshots") < sql.indexOf("DELETE FROM odds_markets"))
        assertTrue(sql.indexOf("DELETE FROM odds_match_links") < sql.indexOf("DELETE FROM odds_platform_matches"))
    }
}
