package com.wrbug.polymarketbot.service.system

import com.wrbug.polymarketbot.dto.NotificationTemplateDto
import com.wrbug.polymarketbot.dto.TemplateTypeInfoDto
import com.wrbug.polymarketbot.dto.TemplateVariableCategoryDto
import com.wrbug.polymarketbot.dto.TemplateVariableDto
import com.wrbug.polymarketbot.dto.TemplateVariablesResponse
import com.wrbug.polymarketbot.entity.NotificationTemplate
import com.wrbug.polymarketbot.repository.NotificationTemplateRepository
import java.nio.charset.Charset
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Lazy
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class NotificationTemplateService(
    private val templateRepository: NotificationTemplateRepository,
    @Lazy private val telegramNotificationService: TelegramNotificationService
) {
    private val logger = LoggerFactory.getLogger(NotificationTemplateService::class.java)

    companion object {
        val TEMPLATE_TYPES = mapOf(
            "ORDER_SUCCESS" to TemplateTypeInfoDto(
                type = "ORDER_SUCCESS",
                name = "Order Success",
                description = "Notification sent when an order is created successfully"
            ),
            "ORDER_FAILED" to TemplateTypeInfoDto(
                type = "ORDER_FAILED",
                name = "Order Failed",
                description = "Notification sent when order creation fails"
            ),
            "ORDER_FILTERED" to TemplateTypeInfoDto(
                type = "ORDER_FILTERED",
                name = "Order Filtered",
                description = "Notification sent when an order is skipped by filters"
            ),
            "REDEEM_SUCCESS" to TemplateTypeInfoDto(
                type = "REDEEM_SUCCESS",
                name = "Redeem Success",
                description = "Notification sent when redeem succeeds"
            ),
            "REDEEM_NO_RETURN" to TemplateTypeInfoDto(
                type = "REDEEM_NO_RETURN",
                name = "Redeem No Return",
                description = "Notification sent when settlement returns zero"
            ),
            "MONITOR_PUSH" to TemplateTypeInfoDto(
                type = "MONITOR_PUSH",
                name = "Monitor Push",
                description = "Notification sent for a monitored leader's single trade and current position"
            ),
            "MONITOR_SAME_SIDE" to TemplateTypeInfoDto(
                type = "MONITOR_SAME_SIDE",
                name = "Monitor Same Side",
                description = "Notification sent when multiple monitored leaders hold the same side"
            ),
            "MONITOR_OPPOSITE_SIDE" to TemplateTypeInfoDto(
                type = "MONITOR_OPPOSITE_SIDE",
                name = "Monitor Opposite Side",
                description = "Notification sent when monitored leaders hold opposite sides or hedge"
            )
        )

        val VARIABLE_CATEGORIES = listOf(
            TemplateVariableCategoryDto("common", 0),
            TemplateVariableCategoryDto("order", 10),
            TemplateVariableCategoryDto("copy_trading", 20),
            TemplateVariableCategoryDto("monitor", 30),
            TemplateVariableCategoryDto("redeem", 40),
            TemplateVariableCategoryDto("error", 50),
            TemplateVariableCategoryDto("filter", 60),
            TemplateVariableCategoryDto("strategy", 70)
        )

        val TEMPLATE_VARIABLES = mapOf(
            "ORDER_SUCCESS" to listOf(
                TemplateVariableDto("account_name", "common", 1),
                TemplateVariableDto("wallet_address", "common", 2),
                TemplateVariableDto("time", "common", 3),
                TemplateVariableDto("order_id", "order", 10),
                TemplateVariableDto("market_title", "order", 11),
                TemplateVariableDto("market_link", "order", 12),
                TemplateVariableDto("side", "order", 13),
                TemplateVariableDto("outcome", "order", 14),
                TemplateVariableDto("price", "order", 15),
                TemplateVariableDto("quantity", "order", 16),
                TemplateVariableDto("amount", "order", 17),
                TemplateVariableDto("available_balance", "order", 18),
                TemplateVariableDto("leader_name", "copy_trading", 21),
                TemplateVariableDto("config_name", "copy_trading", 22)
            ),
            "ORDER_FAILED" to listOf(
                TemplateVariableDto("account_name", "common", 1),
                TemplateVariableDto("wallet_address", "common", 2),
                TemplateVariableDto("time", "common", 3),
                TemplateVariableDto("market_title", "order", 10),
                TemplateVariableDto("market_link", "order", 11),
                TemplateVariableDto("side", "order", 12),
                TemplateVariableDto("outcome", "order", 13),
                TemplateVariableDto("price", "order", 14),
                TemplateVariableDto("quantity", "order", 15),
                TemplateVariableDto("amount", "order", 16),
                TemplateVariableDto("leader_name", "copy_trading", 21),
                TemplateVariableDto("config_name", "copy_trading", 22),
                TemplateVariableDto("error_message", "error", 20)
            ),
            "ORDER_FILTERED" to listOf(
                TemplateVariableDto("account_name", "common", 1),
                TemplateVariableDto("wallet_address", "common", 2),
                TemplateVariableDto("time", "common", 3),
                TemplateVariableDto("market_title", "order", 10),
                TemplateVariableDto("market_link", "order", 11),
                TemplateVariableDto("side", "order", 12),
                TemplateVariableDto("outcome", "order", 13),
                TemplateVariableDto("price", "order", 14),
                TemplateVariableDto("quantity", "order", 15),
                TemplateVariableDto("amount", "order", 16),
                TemplateVariableDto("filter_type", "filter", 20),
                TemplateVariableDto("filter_reason", "filter", 21)
            ),
            "REDEEM_SUCCESS" to listOf(
                TemplateVariableDto("account_name", "common", 1),
                TemplateVariableDto("wallet_address", "common", 2),
                TemplateVariableDto("time", "common", 3),
                TemplateVariableDto("transaction_hash", "redeem", 10),
                TemplateVariableDto("total_value", "redeem", 11),
                TemplateVariableDto("available_balance", "redeem", 12)
            ),
            "REDEEM_NO_RETURN" to listOf(
                TemplateVariableDto("account_name", "common", 1),
                TemplateVariableDto("wallet_address", "common", 2),
                TemplateVariableDto("time", "common", 3),
                TemplateVariableDto("transaction_hash", "redeem", 10),
                TemplateVariableDto("available_balance", "redeem", 11)
            ),
            "MONITOR_PUSH" to listOf(
                TemplateVariableDto("market_title", "order", 10),
                TemplateVariableDto("market_link", "order", 11),
                TemplateVariableDto("leader_name", "monitor", 20),
                TemplateVariableDto("side", "monitor", 21),
                TemplateVariableDto("outcome", "monitor", 22),
                TemplateVariableDto("price", "monitor", 23),
                TemplateVariableDto("quantity", "monitor", 24),
                TemplateVariableDto("amount", "monitor", 25),
                TemplateVariableDto("current_position_summary", "monitor", 26),
                TemplateVariableDto("time", "common", 3)
            ),
            "MONITOR_SAME_SIDE" to listOf(
                TemplateVariableDto("market_title", "order", 10),
                TemplateVariableDto("market_link", "order", 11),
                TemplateVariableDto("outcome", "monitor", 20),
                TemplateVariableDto("same_side_position_report", "monitor", 21),
                TemplateVariableDto("same_side_count", "monitor", 22),
                TemplateVariableDto("time", "common", 3)
            ),
            "MONITOR_OPPOSITE_SIDE" to listOf(
                TemplateVariableDto("market_title", "order", 10),
                TemplateVariableDto("market_link", "order", 11),
                TemplateVariableDto("outcome_a", "monitor", 20),
                TemplateVariableDto("side_a_position_report", "monitor", 21),
                TemplateVariableDto("outcome_b", "monitor", 22),
                TemplateVariableDto("side_b_position_report", "monitor", 23),
                TemplateVariableDto("hedge_position_report", "monitor", 24),
                TemplateVariableDto("time", "common", 3)
            )
        )

        val DEFAULT_TEMPLATES = mapOf(
            "ORDER_SUCCESS" to """
<b>订单创建成功</b>

<b>订单信息</b>
- 订单ID: <code>{{order_id}}</code>
- 市场: <a href="{{market_link}}">{{market_title}}</a>
- 选择项: <b>{{outcome}}</b>
- 方向: <b>{{side}}</b>
- 价格: <code>{{price}}</code>
- 数量: <code>{{quantity}}</code> 份
- 金额: <code>{{amount}}</code> USDC
- 账号: {{account_name}}
- 可用余额: <code>{{available_balance}}</code> USDC

时间: <code>{{time}}</code>
            """.trimIndent(),
            "ORDER_FAILED" to """
<b>订单创建失败</b>

<b>订单信息</b>
- 市场: <a href="{{market_link}}">{{market_title}}</a>
- 选择项: <b>{{outcome}}</b>
- 方向: <b>{{side}}</b>
- 价格: <code>{{price}}</code>
- 数量: <code>{{quantity}}</code> 份
- 金额: <code>{{amount}}</code> USDC
- 账号: {{account_name}}

<b>失败原因</b>
<code>{{error_message}}</code>

时间: <code>{{time}}</code>
            """.trimIndent(),
            "ORDER_FILTERED" to """
<b>订单已过滤</b>

<b>订单信息</b>
- 市场: <a href="{{market_link}}">{{market_title}}</a>
- 选择项: <b>{{outcome}}</b>
- 方向: <b>{{side}}</b>
- 价格: <code>{{price}}</code>
- 数量: <code>{{quantity}}</code> 份
- 金额: <code>{{amount}}</code> USDC
- 账号: {{account_name}}

<b>过滤类型</b> <code>{{filter_type}}</code>

<b>过滤原因</b>
<code>{{filter_reason}}</code>

时间: <code>{{time}}</code>
            """.trimIndent(),
            "REDEEM_SUCCESS" to """
<b>资金赎回成功</b>

<b>账户信息</b>
- 账号: {{account_name}}
- 交易哈希: <code>{{transaction_hash}}</code>
- 赎回总额: <code>{{total_value}}</code> USDC
- 可用余额: <code>{{available_balance}}</code> USDC

时间: <code>{{time}}</code>
            """.trimIndent(),
            "REDEEM_NO_RETURN" to """
<b>资金赎回未返回金额</b>

<b>账户信息</b>
<i>链上交易已提交，但本次未检测到可入账金额，赎回金额按 0 处理。</i>

- 账号: {{account_name}}
- 交易哈希: <code>{{transaction_hash}}</code>
- 可用余额: <code>{{available_balance}}</code> USDC

时间: <code>{{time}}</code>
            """.trimIndent(),
            "MONITOR_PUSH" to """
<b>监控提醒</b>

<b>市场</b>
<a href="{{market_link}}">{{market_title}}</a>

<b>下注人</b>
{{leader_name}}

<b>交易信息</b>
- 方向: <b>{{side}}</b>
- 选择项: <b>{{outcome}}</b>
- 价格: <code>{{price}}</code>
- 数量: <code>{{quantity}}</code>
- 金额: <code>{{amount}}</code> USDC

<b>当前仓位</b>
<code>{{current_position_summary}}</code>

<b>时间</b>
<code>{{time}}</code>
            """.trimIndent(),
            "MONITOR_SAME_SIDE" to """
<b>同向提醒</b>

<b>市场</b>
<a href="{{market_link}}">{{market_title}}</a>

<b>选择项</b>
<b>{{outcome}}</b>

<b>同向仓位</b>
{{same_side_position_report}}

<b>统计</b>
- 同向人数: <code>{{same_side_count}}</code>

<b>时间</b>
<code>{{time}}</code>
            """.trimIndent(),
            "MONITOR_OPPOSITE_SIDE" to """
<b>反向提醒</b>

<b>市场</b>
<a href="{{market_link}}">{{market_title}}</a>

<b>{{outcome_a}}</b>
{{side_a_position_report}}

<b>{{outcome_b}}</b>
{{side_b_position_report}}

<b>对冲情况</b>
{{hedge_position_report}}

<b>时间</b>
<code>{{time}}</code>
            """.trimIndent()
        )

        private val LEGACY_GBK_CHARSET: Charset = Charset.forName("GBK")

        private fun legacyMojibakeMarker(text: String): String {
            return String(text.toByteArray(Charsets.UTF_8), LEGACY_GBK_CHARSET)
        }

        private val CORRUPTED_TEMPLATE_MARKERS = listOf(
            "????",
            "\uFFFD",
            legacyMojibakeMarker("订单"),
            legacyMojibakeMarker("时间"),
            legacyMojibakeMarker("市场"),
            legacyMojibakeMarker("信息"),
            legacyMojibakeMarker("过滤"),
            legacyMojibakeMarker("仓位")
        )
    }

    fun getTemplateTypes(): List<TemplateTypeInfoDto> {
        return TEMPLATE_TYPES.values.toList()
    }

    fun isSupportedTemplateType(templateType: String): Boolean {
        return TEMPLATE_TYPES.containsKey(templateType)
    }

    fun getAllTemplates(): List<NotificationTemplateDto> {
        return templateRepository.findAll()
            .filter { isSupportedTemplateType(it.templateType) }
            .map { it.toDto(resolveTemplateContent(it)) }
    }

    fun getTemplate(templateType: String): NotificationTemplateDto? {
        if (!isSupportedTemplateType(templateType)) {
            return null
        }

        return templateRepository.findByTemplateType(templateType)?.let { template ->
            NotificationTemplateDto(
                id = template.id,
                templateType = template.templateType,
                templateContent = resolveTemplateContent(template),
                isDefault = template.isDefault,
                createdAt = template.createdAt,
                updatedAt = template.updatedAt
            )
        } ?: DEFAULT_TEMPLATES[templateType]?.let {
            NotificationTemplateDto(
                templateType = templateType,
                templateContent = it,
                isDefault = true
            )
        }
    }

    fun getTemplateVariables(templateType: String): TemplateVariablesResponse? {
        if (!isSupportedTemplateType(templateType)) {
            return null
        }

        val variables = TEMPLATE_VARIABLES[templateType] ?: emptyList()
        val usedCategories = variables.map { it.category }.toSet()
        val categories = VARIABLE_CATEGORIES.filter { usedCategories.contains(it.key) }

        return TemplateVariablesResponse(
            templateType = templateType,
            categories = categories,
            variables = variables
        )
    }

    @Transactional
    fun updateTemplate(templateType: String, content: String): NotificationTemplateDto {
        require(isSupportedTemplateType(templateType)) { "Unsupported template type: $templateType" }

        val template = templateRepository.findByTemplateType(templateType)
        val now = System.currentTimeMillis()

        return if (template != null) {
            template.templateContent = content
            template.isDefault = false
            template.updatedAt = now
            templateRepository.save(template).toDto()
        } else {
            templateRepository.save(
                NotificationTemplate(
                    templateType = templateType,
                    templateContent = content,
                    isDefault = false,
                    createdAt = now,
                    updatedAt = now
                )
            ).toDto()
        }
    }

    @Transactional
    fun resetTemplate(templateType: String): NotificationTemplateDto? {
        if (!isSupportedTemplateType(templateType)) {
            return null
        }

        val defaultContent = DEFAULT_TEMPLATES[templateType] ?: return null
        val template = templateRepository.findByTemplateType(templateType)
        val now = System.currentTimeMillis()

        return if (template != null) {
            template.templateContent = defaultContent
            template.isDefault = true
            template.updatedAt = now
            templateRepository.save(template).toDto()
        } else {
            templateRepository.save(
                NotificationTemplate(
                    templateType = templateType,
                    templateContent = defaultContent,
                    isDefault = true,
                    createdAt = now,
                    updatedAt = now
                )
            ).toDto()
        }
    }

    fun renderTemplate(templateType: String, variables: Map<String, String>): String {
        val content = getTemplate(templateType)?.templateContent
            ?: DEFAULT_TEMPLATES[templateType]
            ?: return ""

        return renderTemplateContent(content, variables)
    }

    fun renderTemplateContent(content: String, variables: Map<String, String>): String {
        val requiredVariables = extractTemplateVariables(content)
        var result = content

        requiredVariables.forEach { variableName ->
            result = result.replace("{{$variableName}}", variables[variableName] ?: "-")
        }

        return result
    }

    fun filterVariablesForTemplate(templateType: String, variables: Map<String, String>): Map<String, String> {
        val content = getTemplate(templateType)?.templateContent
            ?: DEFAULT_TEMPLATES[templateType]
            ?: return emptyMap()

        val requiredVariables = extractTemplateVariables(content)
        return variables.filterKeys { it in requiredVariables }
    }

    suspend fun sendTestMessage(templateType: String, content: String? = null): Boolean {
        if (!isSupportedTemplateType(templateType)) {
            return false
        }

        val templateContent = content ?: getTemplate(templateType)?.templateContent ?: return false
        val testVariables = generateTestVariables()
        val message = renderTemplateContent(templateContent, testVariables)

        return try {
            telegramNotificationService.sendMessage(message)
            true
        } catch (e: Exception) {
            logger.error("Failed to send template test message: {}", e.message, e)
            false
        }
    }

    private fun extractTemplateVariables(content: String): Set<String> {
        val regex = Regex("\\{\\{([^}]+)}}")
        return regex.findAll(content)
            .map { it.groupValues[1].trim() }
            .toSet()
    }

    private fun generateTestVariables(): Map<String, String> {
        return mapOf(
            "account_name" to "测试账户",
            "wallet_address" to "0x1234...5678",
            "time" to "2026-04-23 20:00:00",
            "order_id" to "12345678",
            "market_title" to "测试市场标题",
            "market_link" to "https://polymarket.com/event/test",
            "side" to "买入",
            "outcome" to "YES",
            "price" to "0.55",
            "quantity" to "100",
            "amount" to "55.00",
            "available_balance" to "1000.00",
            "leader_name" to "测试Leader",
            "config_name" to "测试配置",
            "error_message" to "余额不足",
            "filter_type" to "价差过大",
            "filter_reason" to "当前市场价差超过限制",
            "strategy_name" to "测试策略",
            "transaction_hash" to "0xabcd...efgh",
            "total_value" to "100.00",
            "current_position_summary" to "YES 320u / NO 95u",
            "same_side_position_report" to "• Austin｜持仓报告: <code>320u @ 0.61</code>",
            "same_side_count" to "2",
            "outcome_a" to "YES",
            "side_a_position_report" to "• Austin｜持仓报告: <code>320u @ 0.61</code>",
            "outcome_b" to "NO",
            "side_b_position_report" to "• debased｜持仓报告: <code>185u @ 0.42</code>",
            "hedge_position_report" to "• hedger｜YES: <code>210u @ 0.59</code>｜NO: <code>95u @ 0.41</code>"
        )
    }

    private fun resolveTemplateContent(template: NotificationTemplate): String {
        val bundledDefault = DEFAULT_TEMPLATES[template.templateType]

        if (template.isDefault) {
            return bundledDefault ?: template.templateContent
        }

        if (bundledDefault != null && looksCorruptedTemplateContent(template.templateContent)) {
            logger.warn(
                "Detected corrupted notification template content, falling back to bundled default: templateType={}",
                template.templateType
            )
            return bundledDefault
        }

        return template.templateContent
    }

    private fun looksCorruptedTemplateContent(content: String): Boolean {
        if (content.isBlank()) {
            return true
        }

        return CORRUPTED_TEMPLATE_MARKERS.any { content.contains(it) }
    }

    private fun NotificationTemplate.toDto(templateContent: String = this.templateContent) = NotificationTemplateDto(
        id = id,
        templateType = templateType,
        templateContent = templateContent,
        isDefault = isDefault,
        createdAt = createdAt,
        updatedAt = updatedAt
    )
}
