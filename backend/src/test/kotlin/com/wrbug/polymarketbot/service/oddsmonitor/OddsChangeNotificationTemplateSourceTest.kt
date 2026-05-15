package com.wrbug.polymarketbot.service.oddsmonitor

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OddsChangeNotificationTemplateSourceTest {

    @Test
    fun `odds change notifications should render prematch and live templates`() {
        val source = Files.readString(
            Path.of("src/main/kotlin/com/wrbug/polymarketbot/service/oddsmonitor/OddsChangeNotificationService.kt")
        )

        assertTrue(source.contains("notificationTemplateService"))
        assertTrue(source.contains("renderTemplate("))
        assertTrue(source.contains("ODDS_PREMATCH_PUSH"))
        assertTrue(source.contains("ODDS_LIVE_PUSH"))
    }
}
