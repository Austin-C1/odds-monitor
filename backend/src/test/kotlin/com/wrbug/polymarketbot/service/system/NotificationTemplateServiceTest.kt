package com.wrbug.polymarketbot.service.system

import com.wrbug.polymarketbot.entity.NotificationTemplate
import com.wrbug.polymarketbot.repository.NotificationTemplateRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock

class NotificationTemplateServiceTest {

    private val templateRepository = mock(NotificationTemplateRepository::class.java)
    private val telegramNotificationService = mock(TelegramNotificationService::class.java)
    private val service = NotificationTemplateService(templateRepository, telegramNotificationService)

    @Test
    fun `template types should only expose prematch live and betting templates`() {
        val templateTypes = service.getTemplateTypes().map { it.type }

        assertEquals(
            listOf("ODDS_PREMATCH_PUSH", "ODDS_LIVE_PUSH", "BETTING_TEMPLATE"),
            templateTypes
        )
        assertFalse(templateTypes.contains("ORDER_SUCCESS"))
        assertFalse(templateTypes.contains("REDEEM_SUCCESS"))
        assertFalse(templateTypes.contains("MONITOR_SAME_SIDE"))
        assertFalse(templateTypes.contains("MONITOR_OPPOSITE_SIDE"))
    }

    @Test
    fun `removed notification templates should not expose configuration variables`() {
        listOf(
            "ORDER_SUCCESS",
            "ORDER_FAILED",
            "ORDER_FILTERED",
            "REDEEM_SUCCESS",
            "REDEEM_NO_RETURN",
            "MONITOR_PUSH",
            "MONITOR_SAME_SIDE",
            "MONITOR_OPPOSITE_SIDE",
            "CRYPTO_TAIL_SUCCESS"
        ).forEach { templateType ->
            assertNull(service.getTemplateVariables(templateType), "$templateType should not be configurable")
        }
    }

    @Test
    fun `prematch odds template should expose monitor variables`() {
        val response = service.getTemplateVariables("ODDS_PREMATCH_PUSH")!!
        val variables = response.variables.map { it.key }
        val categories = response.categories.map { it.key }

        assertTrue(variables.contains("match_title"))
        assertTrue(variables.contains("league_name"))
        assertTrue(variables.contains("market_lines"))
        assertTrue(variables.contains("filter_summary"))
        assertTrue(variables.contains("time"))
        assertFalse(variables.contains("order_id"))
        assertTrue(categories.contains("monitor"))
    }

    @Test
    fun `live odds template should expose live match variables`() {
        val variables = service.getTemplateVariables("ODDS_LIVE_PUSH")!!.variables.map { it.key }

        assertTrue(variables.contains("match_title"))
        assertTrue(variables.contains("elapsed_minutes"))
        assertTrue(variables.contains("score_text"))
        assertTrue(variables.contains("market_lines"))
        assertFalse(variables.contains("same_side_position_report"))
        assertFalse(variables.contains("hedge_position_report"))
    }

    @Test
    fun `betting template should be reserved as configurable placeholder`() {
        val response = service.getTemplateVariables("BETTING_TEMPLATE")!!
        val variables = response.variables.map { it.key }
        val categories = response.categories.map { it.key }
        val template = service.getTemplate("BETTING_TEMPLATE")!!

        assertTrue(variables.contains("market_title"))
        assertTrue(variables.contains("selection_name"))
        assertTrue(variables.contains("odds"))
        assertTrue(variables.contains("amount"))
        assertTrue(categories.contains("betting"))
        assertTrue(template.templateContent.contains("投注模板"))
    }

    @Test
    fun `default template rows should use bundled focused content instead of stale database text`() {
        `when`(templateRepository.findByTemplateType("ODDS_PREMATCH_PUSH")).thenReturn(
            NotificationTemplate(
                id = 1L,
                templateType = "ODDS_PREMATCH_PUSH",
                templateContent = "legacy template content",
                isDefault = true
            )
        )

        val template = service.getTemplate("ODDS_PREMATCH_PUSH")

        assertNotEquals("legacy template content", template!!.templateContent)
        assertTrue(template.templateContent.contains("赛前赔率变动"))
    }

    @Test
    fun `bundled configurable templates should not contain stale order text`() {
        listOf("ODDS_PREMATCH_PUSH", "ODDS_LIVE_PUSH", "BETTING_TEMPLATE").forEach { templateType ->
            val content = service.getTemplate(templateType)!!.templateContent

            assertFalse(content.contains("????"), "$templateType should not contain replacement question marks")
            assertFalse(content.contains("shares"), "$templateType should use Chinese units")
            assertFalse(content.contains("<b>Leader</b>"), "$templateType should use Chinese labels")
            assertFalse(content.contains("订单创建"), "$templateType should not use old order templates")
        }
    }

    @Test
    fun `corrupted custom template rows should fall back to bundled content`() {
        val corruptedContent = "???? <b>broken template</b>"
        `when`(templateRepository.findByTemplateType("ODDS_PREMATCH_PUSH")).thenReturn(
            NotificationTemplate(
                id = 2L,
                templateType = "ODDS_PREMATCH_PUSH",
                templateContent = corruptedContent,
                isDefault = false
            )
        )

        val template = service.getTemplate("ODDS_PREMATCH_PUSH")

        assertTrue(template!!.templateContent.contains("赛前赔率变动"))
        assertFalse(
            template.templateContent.contains(corruptedContent),
            "ODDS_PREMATCH_PUSH should not leak corrupted template content"
        )
    }
}
