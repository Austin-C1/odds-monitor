package com.wrbug.polymarketbot.service.system

import com.wrbug.polymarketbot.entity.NotificationTemplate
import com.wrbug.polymarketbot.repository.NotificationTemplateRepository
import java.nio.charset.Charset
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
    private val legacyGbkCharset: Charset = Charset.forName("GBK")

    private fun legacyMojibake(text: String): String {
        return String(text.toByteArray(Charsets.UTF_8), legacyGbkCharset)
    }

    @Test
    fun `template types should not expose crypto tail success`() {
        val templateTypes = service.getTemplateTypes().map { it.type }

        assertFalse(templateTypes.contains("CRYPTO_TAIL_SUCCESS"))
    }

    @Test
    fun `template types should expose monitor push`() {
        val templateTypes = service.getTemplateTypes().map { it.type }

        assertTrue(templateTypes.contains("MONITOR_PUSH"))
    }

    @Test
    fun `removed crypto tail template should not expose variables`() {
        assertNull(service.getTemplateVariables("CRYPTO_TAIL_SUCCESS"))
    }

    @Test
    fun `order failed template should expose leader name variable`() {
        val variables = service.getTemplateVariables("ORDER_FAILED")!!.variables.map { it.key }

        assertTrue(
            variables.contains("leader_name"),
            "ORDER_FAILED should expose leader_name so failure notifications can show signal source"
        )
    }

    @Test
    fun `order failed template should expose config name variable`() {
        val variables = service.getTemplateVariables("ORDER_FAILED")!!.variables.map { it.key }

        assertTrue(
            variables.contains("config_name"),
            "ORDER_FAILED should expose config_name so failure notifications can show the exact copy-trading source"
        )
    }

    @Test
    fun `monitor same side template should expose position report variables`() {
        val variables = service.getTemplateVariables("MONITOR_SAME_SIDE")!!.variables.map { it.key }

        assertTrue(variables.contains("same_side_position_report"))
        assertTrue(variables.contains("same_side_count"))
    }

    @Test
    fun `monitor opposite template should expose hedge position report variables`() {
        val variables = service.getTemplateVariables("MONITOR_OPPOSITE_SIDE")!!.variables.map { it.key }

        assertTrue(variables.contains("side_a_position_report"))
        assertTrue(variables.contains("side_b_position_report"))
        assertTrue(variables.contains("hedge_position_report"))
    }

    @Test
    fun `monitor push template should expose current position summary variable`() {
        val response = service.getTemplateVariables("MONITOR_PUSH")!!
        val variables = response.variables.map { it.key }
        val categories = response.categories.map { it.key }

        assertTrue(variables.contains("leader_name"))
        assertTrue(variables.contains("current_position_summary"))
        assertTrue(categories.contains("monitor"))
    }

    @Test
    fun `default template rows should use bundled content instead of stale database text`() {
        `when`(templateRepository.findByTemplateType("ORDER_FAILED")).thenReturn(
            NotificationTemplate(
                id = 1L,
                templateType = "ORDER_FAILED",
                templateContent = "legacy template content",
                isDefault = true
            )
        )

        val template = service.getTemplate("ORDER_FAILED")

        assertNotEquals("legacy template content", template!!.templateContent)
        assertTrue(
            template.templateContent.contains("订单创建失败"),
            "ORDER_FAILED should use the bundled default content when the saved row is still marked as default"
        )
    }

    @Test
    fun `bundled default telegram templates should not contain mojibake or stale english units`() {
        listOf("ORDER_SUCCESS", "ORDER_FAILED", "ORDER_FILTERED", "MONITOR_PUSH").forEach { templateType ->
            val content = service.getTemplate(templateType)!!.templateContent

            assertFalse(content.contains("????"), "$templateType should not contain replacement question marks")
            assertFalse(content.contains("shares"), "$templateType should use Chinese units")
            assertFalse(content.contains("<b>Leader</b>"), "$templateType should use Chinese labels")
        }
    }

    @Test
    fun `legacy mojibake custom template rows should fall back to bundled content`() {
        `when`(templateRepository.findByTemplateType("ORDER_SUCCESS")).thenReturn(
            NotificationTemplate(
                id = 2L,
                templateType = "ORDER_SUCCESS",
                templateContent = legacyMojibake(
                    """
                    🚀 <b>订单创建成功</b>

                    📊 <b>订单信息</b>
                    • 账户: 主账户
                    • 时间: 2024-01-15 12:30:00
                    """.trimIndent()
                ),
                isDefault = false
            )
        )

        val template = service.getTemplate("ORDER_SUCCESS")

        assertTrue(
            template!!.templateContent.contains("订单创建成功"),
            "ORDER_SUCCESS should ignore legacy mojibake content and fall back to the bundled default"
        )
        assertFalse(
            template.templateContent.contains(legacyMojibake("订单")),
            "ORDER_SUCCESS should not leak legacy mojibake markers into returned template content"
        )
    }
}
