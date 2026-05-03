package com.wrbug.polymarketbot.service.oddsmonitor.collector.pinnacle

import com.microsoft.playwright.Page
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@Component
class PinnacleDebugArtifactService {
    private val root = Path.of("output", "pinnacle", "debug")
    private val formatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

    fun saveHtml(html: String, reason: String): Path {
        val dir = createDebugDir(reason)
        val path = dir.resolve("page.html")
        Files.writeString(path, html)
        return path
    }

    fun saveScreenshot(page: Page?, reason: String): Path? {
        if (page == null) {
            return null
        }
        val dir = createDebugDir(reason)
        val path = dir.resolve("screenshot.png")
        page.screenshot(Page.ScreenshotOptions().setPath(path).setFullPage(false))
        return path
    }

    fun saveHtmlAndScreenshot(html: String?, page: Page?, reason: String): String {
        val paths = mutableListOf<String>()
        if (!html.isNullOrBlank()) {
            paths += saveHtml(html, reason).toString()
        }
        saveScreenshot(page, reason)?.let { paths += it.toString() }
        return paths.joinToString(", ")
    }

    private fun createDebugDir(reason: String): Path {
        val cleanReason = reason.replace(Regex("[^A-Za-z0-9_-]"), "_").take(32)
        val dir = root.resolve("${LocalDateTime.now().format(formatter)}-$cleanReason")
        Files.createDirectories(dir)
        return dir
    }
}
