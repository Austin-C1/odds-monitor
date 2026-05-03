package com.wrbug.polymarketbot.service.oddsmonitor.collector.pinnacle

import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserContext
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Locator
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path

@Component
class PinnacleBrowserSession(
    private val pageReadiness: PinnaclePageReadiness,
    @Value("\${pinnacle.browser.headless:false}")
    private val headless: Boolean
) {
    private val sessionDir = Path.of("output", "pinnacle", "session")
    private val storageStatePath = sessionDir.resolve("cookies.json")

    private var playwright: Playwright? = null
    private var browser: Browser? = null
    private var context: BrowserContext? = null
    private var page: Page? = null

    fun page(): Page {
        if (page == null || page?.isClosed == true) {
            start()
        }
        return page ?: throw PinnacleCollectionException("failed_browser", "browser page unavailable")
    }

    fun activePage(): Page? = page

    fun saveSession() {
        val currentContext = context ?: return
        Files.createDirectories(sessionDir)
        currentContext.storageState(BrowserContext.StorageStateOptions().setPath(storageStatePath))
    }

    fun close() {
        page = null
        context?.close()
        browser?.close()
        playwright?.close()
        context = null
        browser = null
        playwright = null
    }

    fun scrollToBottom(maxScrolls: Int = 5, delayMillis: Double = 1500.0) {
        val currentPage = page()
        var lastHeight = (currentPage.evaluate("document.body.scrollHeight") as Number).toInt()
        var stableCount = 0
        repeat(maxScrolls) {
            currentPage.evaluate("window.scrollTo(0, document.body.scrollHeight)")
            currentPage.waitForTimeout(delayMillis)
            val newHeight = (currentPage.evaluate("document.body.scrollHeight") as Number).toInt()
            if (newHeight == lastHeight) {
                stableCount += 1
                if (stableCount >= 2) {
                    return@repeat
                }
            } else {
                stableCount = 0
                lastHeight = newHeight
            }
        }
        currentPage.evaluate("window.scrollTo(0, 0)")
    }

    fun dismissBlockingPrompts() {
        val currentPage = page()
        listOf(
            """button:has-text("否")""",
            """button:has-text("稍候提醒我")""",
            """.AlertComponent .close""",
            """.alert-overlay .close"""
        ).forEach { selector ->
            runCatching {
                val locator = currentPage.locator(selector).first()
                if (locator.count() > 0 && locator.isVisible) {
                    locator.click(Locator.ClickOptions().setTimeout(1_000.0))
                    currentPage.waitForTimeout(500.0)
                }
            }
        }
    }

    fun waitForOddsContent(timeoutMillis: Long = 30_000): String {
        val currentPage = page()
        val deadline = System.currentTimeMillis() + timeoutMillis
        var lastHtml = currentPage.content()
        while (System.currentTimeMillis() < deadline) {
            dismissBlockingPrompts()
            lastHtml = currentPage.content()
            if (pageReadiness.hasLoadedOdds(lastHtml)) {
                return lastHtml
            }
            currentPage.waitForTimeout(1_000.0)
        }
        return lastHtml
    }

    private fun start() {
        Files.createDirectories(sessionDir)
        playwright = Playwright.create()
        browser = playwright?.chromium()?.launch(
            BrowserType.LaunchOptions()
                .setHeadless(headless)
                .setTimeout(60_000.0)
                .setArgs(
                    listOf(
                        "--disable-blink-features=AutomationControlled",
                        "--no-sandbox",
                        "--disable-dev-shm-usage",
                        "--disable-setuid-sandbox",
                        "--disable-web-security"
                    )
                )
        )

        val options = Browser.NewContextOptions()
            .setViewportSize(1920, 1080)
            .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .setLocale("zh-CN")
            .setTimezoneId("Asia/Shanghai")
            .setIgnoreHTTPSErrors(true)

        if (Files.exists(storageStatePath)) {
            options.setStorageStatePath(storageStatePath)
        }

        context = browser?.newContext(options)
        page = context?.newPage()
    }
}
