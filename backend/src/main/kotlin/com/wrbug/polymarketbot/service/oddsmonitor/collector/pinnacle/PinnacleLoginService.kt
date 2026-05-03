package com.wrbug.polymarketbot.service.oddsmonitor.collector.pinnacle

import com.microsoft.playwright.Page
import com.wrbug.polymarketbot.entity.OddsDataSourceConfig
import org.springframework.stereotype.Component

@Component
class PinnacleLoginService(
    private val browserSession: PinnacleBrowserSession,
    private val debugArtifactService: PinnacleDebugArtifactService
) {
    private val footballUrl = "https://www.ps3838.com/zh-cn/sports/soccer"

    fun ensureLoggedIn(config: OddsDataSourceConfig): Page {
        val username = config.username?.takeIf { it.isNotBlank() }
            ?: throw PinnacleCollectionException("failed_login", "pinnacle username is not configured")
        val password = config.password?.takeIf { it.isNotBlank() }
            ?: throw PinnacleCollectionException("failed_login", "pinnacle password is not configured")

        val page = browserSession.page()
        navigateToFootball(page)
        if (isLoggedIn(page)) {
            browserSession.saveSession()
            return page
        }

        login(page, username, password)
        if (!isLoggedIn(page)) {
            val artifacts = debugArtifactService.saveHtmlAndScreenshot(page.content(), page, "failed_login")
            throw PinnacleCollectionException("failed_login", "pinnacle login failed; debug: $artifacts")
        }

        browserSession.saveSession()
        return page
    }

    fun openFootballPage(page: Page) {
        navigateToFootball(page)
        if (!isLoggedIn(page)) {
            throw PinnacleCollectionException("failed_login", "pinnacle session expired")
        }
    }

    private fun navigateToFootball(page: Page) {
        try {
            page.navigate(footballUrl, Page.NavigateOptions().setTimeout(60_000.0))
            page.waitForLoadState()
            page.waitForTimeout(3_000.0)
        } catch (ex: RuntimeException) {
            throw PinnacleCollectionException("failed_network", "pinnacle football page unavailable", ex)
        }
    }

    private fun login(page: Page, username: String, password: String) {
        val usernameInput = page.querySelector("""input[name="loginId"]""")
            ?: page.querySelector("""input[type="text"]""")
            ?: throwLoginFormMissing(page)
        val passwordInput = page.querySelector("""input[name="pass"]""")
            ?: page.querySelector("""input[type="password"]""")
            ?: throwLoginFormMissing(page)
        val loginButton = page.querySelector("""button[type="submit"]""")
            ?: page.querySelector("""button:has-text("登录")""")
            ?: page.querySelector("""button:has-text("Login")""")
            ?: throwLoginFormMissing(page)

        usernameInput.fill(username)
        passwordInput.fill(password)
        loginButton.click()
        page.waitForTimeout(5_000.0)
        runCatching { page.waitForLoadState() }
    }

    private fun isLoggedIn(page: Page): Boolean {
        val hasPasswordInput = page.querySelector("""input[type="password"], input[name="pass"]""") != null
        if (hasPasswordInput) {
            return false
        }
        val urlLooksLoggedIn = page.url().contains("/sports/")
        val pageHasEvents = page.querySelector("table.events, div.league, [data-eid]") != null
        return urlLooksLoggedIn || pageHasEvents
    }

    private fun throwLoginFormMissing(page: Page): Nothing {
        val artifacts = debugArtifactService.saveHtmlAndScreenshot(page.content(), page, "login_form_missing")
        throw PinnacleCollectionException("failed_login", "pinnacle login form was not found; debug: $artifacts")
    }
}
