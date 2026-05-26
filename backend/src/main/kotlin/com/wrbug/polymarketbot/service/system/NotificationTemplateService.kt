package com.wrbug.polymarketbot.service.system

import com.wrbug.polymarketbot.dto.NotificationTemplateDto
import com.wrbug.polymarketbot.dto.TemplateTypeInfoDto
import com.wrbug.polymarketbot.dto.TemplateVariableCategoryDto
import com.wrbug.polymarketbot.dto.TemplateVariableDto
import com.wrbug.polymarketbot.dto.TemplateVariablesResponse
import com.wrbug.polymarketbot.entity.NotificationTemplate
import com.wrbug.polymarketbot.repository.NotificationTemplateRepository
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
            "ODDS_PREMATCH_PUSH" to TemplateTypeInfoDto(
                type = "ODDS_PREMATCH_PUSH",
                name = "赛前赔率推送",
                description = "赛前赔率变化时发送的通知"
            ),
            "ODDS_LIVE_PUSH" to TemplateTypeInfoDto(
                type = "ODDS_LIVE_PUSH",
                name = "滚球赔率推送",
                description = "滚球赔率变化时发送的通知"
            ),
            "BETTING_TEMPLATE" to TemplateTypeInfoDto(
                type = "BETTING_TEMPLATE",
                name = "投注成功模板",
                description = "投注成功后自动推送的消息格式"
            )
        )

        val VARIABLE_CATEGORIES = listOf(
            TemplateVariableCategoryDto("common", 0),
            TemplateVariableCategoryDto("monitor", 10),
            TemplateVariableCategoryDto("betting", 20)
        )

        val TEMPLATE_VARIABLES = mapOf(
            "ODDS_PREMATCH_PUSH" to listOf(
                TemplateVariableDto("match_title", "monitor", 10),
                TemplateVariableDto("league_name", "monitor", 11),
                TemplateVariableDto("market_lines", "monitor", 12),
                TemplateVariableDto("filter_summary", "monitor", 13),
                TemplateVariableDto("time", "common", 1)
            ),
            "ODDS_LIVE_PUSH" to listOf(
                TemplateVariableDto("match_title", "monitor", 10),
                TemplateVariableDto("league_name", "monitor", 11),
                TemplateVariableDto("elapsed_minutes", "monitor", 12),
                TemplateVariableDto("score_text", "monitor", 13),
                TemplateVariableDto("market_lines", "monitor", 14),
                TemplateVariableDto("filter_summary", "monitor", 15),
                TemplateVariableDto("time", "common", 1)
            ),
            "BETTING_TEMPLATE" to listOf(
                TemplateVariableDto("account_name", "betting", 8),
                TemplateVariableDto("account_key", "betting", 9),
                TemplateVariableDto("match_title", "betting", 10),
                TemplateVariableDto("league_name", "betting", 11),
                TemplateVariableDto("market_title", "betting", 12),
                TemplateVariableDto("selection_name", "betting", 13),
                TemplateVariableDto("odds", "betting", 14),
                TemplateVariableDto("amount", "betting", 15),
                TemplateVariableDto("time", "common", 1)
            )
        )

        val DEFAULT_TEMPLATES = mapOf(
            "ODDS_PREMATCH_PUSH" to """
<b>赛前赔率变动</b>

联赛：{{league_name}}
比赛：{{match_title}}

{{market_lines}}

筛选：{{filter_summary}}
时间：<code>{{time}}</code>
            """.trimIndent(),
            "ODDS_LIVE_PUSH" to """
<b>滚球赔率变动</b>

联赛：{{league_name}}
比赛：{{match_title}}
进行：{{elapsed_minutes}}
比分：{{score_text}}

{{market_lines}}

筛选：{{filter_summary}}
时间：<code>{{time}}</code>
            """.trimIndent(),
            "BETTING_TEMPLATE" to """
<b>投注成功</b>

账号：{{account_name}}
联赛：{{league_name}}
比赛：{{match_title}}
盘口：{{market_title}}
选择：{{selection_name}}
赔率：<code>{{odds}}</code>
金额：<code>{{amount}}</code>

时间：<code>{{time}}</code>
            """.trimIndent()
        )

        private val CORRUPTED_TEMPLATE_MARKERS = listOf(
            "????",
            "\uFFFD"
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
            "account_name" to "皇冠主号",
            "account_key" to "crown-account-1",
            "match_title" to "Arsenal vs Chelsea",
            "league_name" to "英格兰超级联赛",
            "market_lines" to "盘口：让球 主队 -0.5\n平博：0.91 -> 0.86\n皇冠：0.94 -> 0.90",
            "filter_summary" to "动水通过 / 合水通过",
            "elapsed_minutes" to "第 37 分钟",
            "score_text" to "1-0",
            "market_title" to "让球 主队 -0.5",
            "selection_name" to "主队",
            "odds" to "0.86",
            "amount" to "55.00",
            "time" to "2026-04-23 20:00:00"
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
