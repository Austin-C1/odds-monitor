package com.wrbug.polymarketbot.service.copytrading.templates

import com.wrbug.polymarketbot.dto.*
import com.wrbug.polymarketbot.entity.CopyTradingTemplate
import com.wrbug.polymarketbot.repository.CopyTradingRepository
import com.wrbug.polymarketbot.repository.CopyTradingTemplateRepository
import com.wrbug.polymarketbot.util.toSafeBigDecimal
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal

@Service
class CopyTradingTemplateService(
    private val templateRepository: CopyTradingTemplateRepository,
    private val copyTradingRepository: CopyTradingRepository
) {
    
    private val logger = LoggerFactory.getLogger(CopyTradingTemplateService::class.java)
    
    @Transactional
    fun createTemplate(request: TemplateCreateRequest): Result<TemplateDto> {
        return try {
            if (request.templateName.isBlank()) {
                return Result.failure(IllegalArgumentException("模板名称不能为空"))
            }
            if (templateRepository.existsByTemplateName(request.templateName)) {
                return Result.failure(IllegalArgumentException("模板名称已存在"))
            }
            if (request.copyMode !in listOf("RATIO", "FIXED")) {
                return Result.failure(IllegalArgumentException("copyMode 必须是 RATIO 或 FIXED"))
            }
            val template = CopyTradingTemplate(
                templateName = request.templateName,
                copyMode = request.copyMode,
                copyRatio = request.copyRatio?.toSafeBigDecimal() ?: BigDecimal.ONE,
                fixedAmount = request.fixedAmount?.toSafeBigDecimal(),
                maxOrderSize = request.maxOrderSize?.toSafeBigDecimal() ?: "1000".toSafeBigDecimal(),
                minOrderSize = request.minOrderSize?.toSafeBigDecimal() ?: "1".toSafeBigDecimal(),
                maxDailyLoss = request.maxDailyLoss?.toSafeBigDecimal() ?: "10000".toSafeBigDecimal(),
                maxDailyOrders = request.maxDailyOrders ?: 100,
                priceTolerance = request.priceTolerance?.toSafeBigDecimal() ?: "5".toSafeBigDecimal(),
                delaySeconds = request.delaySeconds ?: 0,
                pollIntervalSeconds = request.pollIntervalSeconds ?: 5,
                useWebSocket = request.useWebSocket ?: true,
                websocketReconnectInterval = request.websocketReconnectInterval ?: 5000,
                websocketMaxRetries = request.websocketMaxRetries ?: 10,
                supportSell = request.supportSell ?: true,
                minOrderDepth = request.minOrderDepth?.toSafeBigDecimal(),
                maxSpread = request.maxSpread?.toSafeBigDecimal(),
                minPrice = request.minPrice?.toSafeBigDecimal(),
                maxPrice = request.maxPrice?.toSafeBigDecimal(),
                pushFilteredOrders = request.pushFilteredOrders ?: false
            )
            
            val saved = templateRepository.save(template)
            
            Result.success(toDto(saved))
        } catch (e: Exception) {
            logger.error("创建模板失败", e)
            Result.failure(e)
        }
    }
    
    @Transactional
    fun updateTemplate(request: TemplateUpdateRequest): Result<TemplateDto> {
        return try {
            val template = templateRepository.findById(request.templateId).orElse(null)
                ?: return Result.failure(IllegalArgumentException("模板不存在"))
            if (request.templateName != null) {
                if (request.templateName.isBlank()) {
                    return Result.failure(IllegalArgumentException("模板名称不能为空"))
                }
                if (request.templateName != template.templateName) {
                    if (templateRepository.existsByTemplateName(request.templateName)) {
                        return Result.failure(IllegalArgumentException("模板名称已存在"))
                    }
                }
            }
            if (request.copyMode != null && request.copyMode !in listOf("RATIO", "FIXED")) {
                return Result.failure(IllegalArgumentException("copyMode 必须是 RATIO 或 FIXED"))
            }
            
            val updated = template.copy(
                templateName = request.templateName ?: template.templateName,
                copyMode = request.copyMode ?: template.copyMode,
                copyRatio = request.copyRatio?.toSafeBigDecimal() ?: template.copyRatio,
                fixedAmount = request.fixedAmount?.toSafeBigDecimal() ?: template.fixedAmount,
                maxOrderSize = request.maxOrderSize?.toSafeBigDecimal() ?: template.maxOrderSize,
                minOrderSize = request.minOrderSize?.toSafeBigDecimal() ?: template.minOrderSize,
                maxDailyLoss = request.maxDailyLoss?.toSafeBigDecimal() ?: template.maxDailyLoss,
                maxDailyOrders = request.maxDailyOrders ?: template.maxDailyOrders,
                priceTolerance = request.priceTolerance?.toSafeBigDecimal() ?: template.priceTolerance,
                delaySeconds = request.delaySeconds ?: template.delaySeconds,
                pollIntervalSeconds = request.pollIntervalSeconds ?: template.pollIntervalSeconds,
                useWebSocket = request.useWebSocket ?: template.useWebSocket,
                websocketReconnectInterval = request.websocketReconnectInterval ?: template.websocketReconnectInterval,
                websocketMaxRetries = request.websocketMaxRetries ?: template.websocketMaxRetries,
                supportSell = request.supportSell ?: template.supportSell,
                minOrderDepth = request.minOrderDepth?.toSafeBigDecimal() ?: template.minOrderDepth,
                maxSpread = request.maxSpread?.toSafeBigDecimal() ?: template.maxSpread,
                minPrice = request.minPrice?.toSafeBigDecimal() ?: template.minPrice,
                maxPrice = request.maxPrice?.toSafeBigDecimal() ?: template.maxPrice,
                pushFilteredOrders = request.pushFilteredOrders ?: template.pushFilteredOrders,
                updatedAt = System.currentTimeMillis()
            )
            
            val saved = templateRepository.save(updated)
            
            Result.success(toDto(saved))
        } catch (e: Exception) {
            logger.error("更新模板失败", e)
            Result.failure(e)
        }
    }
    
    @Transactional
    fun deleteTemplate(templateId: Long): Result<Unit> {
        return try {
            val template = templateRepository.findById(templateId).orElse(null)
                ?: return Result.failure(IllegalArgumentException("模板不存在"))
            templateRepository.delete(template)
            
            Result.success(Unit)
        } catch (e: Exception) {
            logger.error("删除模板失败", e)
            Result.failure(e)
        }
    }
    
    @Transactional
    fun copyTemplate(request: TemplateCopyRequest): Result<TemplateDto> {
        return try {
            val sourceTemplate = templateRepository.findById(request.templateId).orElse(null)
                ?: return Result.failure(IllegalArgumentException("源模板不存在"))
            if (templateRepository.existsByTemplateName(request.templateName)) {
                return Result.failure(IllegalArgumentException("模板名称已存在"))
            }
            val newTemplate = CopyTradingTemplate(
                templateName = request.templateName,
                copyMode = request.copyMode ?: sourceTemplate.copyMode,
                copyRatio = request.copyRatio?.toSafeBigDecimal() ?: sourceTemplate.copyRatio,
                fixedAmount = request.fixedAmount?.toSafeBigDecimal() ?: sourceTemplate.fixedAmount,
                maxOrderSize = request.maxOrderSize?.toSafeBigDecimal() ?: sourceTemplate.maxOrderSize,
                minOrderSize = request.minOrderSize?.toSafeBigDecimal() ?: sourceTemplate.minOrderSize,
                maxDailyLoss = request.maxDailyLoss?.toSafeBigDecimal() ?: sourceTemplate.maxDailyLoss,
                maxDailyOrders = request.maxDailyOrders ?: sourceTemplate.maxDailyOrders,
                priceTolerance = request.priceTolerance?.toSafeBigDecimal() ?: sourceTemplate.priceTolerance,
                delaySeconds = request.delaySeconds ?: sourceTemplate.delaySeconds,
                pollIntervalSeconds = request.pollIntervalSeconds ?: sourceTemplate.pollIntervalSeconds,
                useWebSocket = request.useWebSocket ?: sourceTemplate.useWebSocket,
                websocketReconnectInterval = request.websocketReconnectInterval ?: sourceTemplate.websocketReconnectInterval,
                websocketMaxRetries = request.websocketMaxRetries ?: sourceTemplate.websocketMaxRetries,
                supportSell = request.supportSell ?: sourceTemplate.supportSell,
                minOrderDepth = request.minOrderDepth?.toSafeBigDecimal() ?: sourceTemplate.minOrderDepth,
                maxSpread = request.maxSpread?.toSafeBigDecimal() ?: sourceTemplate.maxSpread,
                minPrice = request.minPrice?.toSafeBigDecimal() ?: sourceTemplate.minPrice,
                maxPrice = request.maxPrice?.toSafeBigDecimal() ?: sourceTemplate.maxPrice,
                pushFilteredOrders = request.pushFilteredOrders ?: sourceTemplate.pushFilteredOrders
            )
            
            val saved = templateRepository.save(newTemplate)
            
            Result.success(toDto(saved))
        } catch (e: Exception) {
            logger.error("复制模板失败", e)
            Result.failure(e)
        }
    }
    
    fun getTemplateList(): Result<TemplateListResponse> {
        return try {
            val templates = templateRepository.findAllByOrderByCreatedAtDesc()
            val templateDtos = templates.map { template ->
                toDto(template)
            }
            
            Result.success(
                TemplateListResponse(
                    list = templateDtos,
                    total = templateDtos.size.toLong()
                )
            )
        } catch (e: Exception) {
            logger.error("查询模板列表失败", e)
            Result.failure(e)
        }
    }
    
    fun getTemplateDetail(templateId: Long): Result<TemplateDto> {
        return try {
            val template = templateRepository.findById(templateId).orElse(null)
                ?: return Result.failure(IllegalArgumentException("模板不存在"))
            
            Result.success(toDto(template))
        } catch (e: Exception) {
            logger.error("查询模板详情失败", e)
            Result.failure(e)
        }
    }
    
    private fun toDto(template: CopyTradingTemplate): TemplateDto {
        return TemplateDto(
            id = template.id!!,
            templateName = template.templateName,
            copyMode = template.copyMode,
            copyRatio = template.copyRatio.toPlainString(),
            fixedAmount = template.fixedAmount?.toPlainString(),
            maxOrderSize = template.maxOrderSize.toPlainString(),
            minOrderSize = template.minOrderSize.toPlainString(),
            maxDailyLoss = template.maxDailyLoss.toPlainString(),
            maxDailyOrders = template.maxDailyOrders,
            priceTolerance = template.priceTolerance.toPlainString(),
            delaySeconds = template.delaySeconds,
            pollIntervalSeconds = template.pollIntervalSeconds,
            useWebSocket = template.useWebSocket,
            websocketReconnectInterval = template.websocketReconnectInterval,
            websocketMaxRetries = template.websocketMaxRetries,
            supportSell = template.supportSell,
            minOrderDepth = template.minOrderDepth?.toPlainString(),
            maxSpread = template.maxSpread?.toPlainString(),
            minPrice = template.minPrice?.toPlainString(),
            maxPrice = template.maxPrice?.toPlainString(),
            pushFilteredOrders = template.pushFilteredOrders,
            createdAt = template.createdAt,
            updatedAt = template.updatedAt
        )
    }
}

