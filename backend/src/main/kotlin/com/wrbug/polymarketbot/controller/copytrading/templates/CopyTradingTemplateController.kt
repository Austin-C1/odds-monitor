package com.wrbug.polymarketbot.controller.copytrading.templates

import com.wrbug.polymarketbot.dto.*
import com.wrbug.polymarketbot.enums.ErrorCode
import com.wrbug.polymarketbot.service.copytrading.templates.CopyTradingTemplateService
import org.slf4j.LoggerFactory
import org.springframework.context.MessageSource
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/copy-trading/templates")
class CopyTradingTemplateController(
    private val templateService: CopyTradingTemplateService,
    private val messageSource: MessageSource
) {
    
    private val logger = LoggerFactory.getLogger(CopyTradingTemplateController::class.java)
    
    @PostMapping("/create")
    fun createTemplate(@RequestBody request: TemplateCreateRequest): ResponseEntity<ApiResponse<TemplateDto>> {
        return try {
            if (request.templateName.isBlank()) {
                return ResponseEntity.ok(ApiResponse.error(ErrorCode.PARAM_TEMPLATE_NAME_EMPTY, messageSource = messageSource))
            }
            
            val result = templateService.createTemplate(request)
            result.fold(
                onSuccess = { template ->
                    ResponseEntity.ok(ApiResponse.success(template))
                },
                onFailure = { e ->
                    logger.error("创建模板失败: ${e.message}", e)
                    when (e) {
                        is IllegalArgumentException -> ResponseEntity.ok(ApiResponse.error(ErrorCode.PARAM_ERROR, e.message, messageSource))
                        else -> ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_TEMPLATE_CREATE_FAILED, e.message, messageSource))
                    }
                }
            )
        } catch (e: Exception) {
            logger.error("创建模板异常: ${e.message}", e)
            ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_TEMPLATE_CREATE_FAILED, e.message, messageSource))
        }
    }
    
    @PostMapping("/update")
    fun updateTemplate(@RequestBody request: TemplateUpdateRequest): ResponseEntity<ApiResponse<TemplateDto>> {
        return try {
            if (request.templateId <= 0) {
                return ResponseEntity.ok(ApiResponse.error(ErrorCode.PARAM_TEMPLATE_ID_INVALID, messageSource = messageSource))
            }
            
            val result = templateService.updateTemplate(request)
            result.fold(
                onSuccess = { template ->
                    ResponseEntity.ok(ApiResponse.success(template))
                },
                onFailure = { e ->
                    logger.error("更新模板失败: ${e.message}", e)
                    when (e) {
                        is IllegalArgumentException -> ResponseEntity.ok(ApiResponse.error(ErrorCode.PARAM_ERROR, e.message, messageSource))
                        else -> ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_TEMPLATE_UPDATE_FAILED, e.message, messageSource))
                    }
                }
            )
        } catch (e: Exception) {
            logger.error("更新模板异常: ${e.message}", e)
            ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_TEMPLATE_UPDATE_FAILED, e.message, messageSource))
        }
    }
    
    @PostMapping("/delete")
    fun deleteTemplate(@RequestBody request: TemplateDeleteRequest): ResponseEntity<ApiResponse<Unit>> {
        return try {
            if (request.templateId <= 0) {
                return ResponseEntity.ok(ApiResponse.error(ErrorCode.PARAM_TEMPLATE_ID_INVALID, messageSource = messageSource))
            }
            
            val result = templateService.deleteTemplate(request.templateId)
            result.fold(
                onSuccess = {
                    ResponseEntity.ok(ApiResponse.success(Unit))
                },
                onFailure = { e ->
                    logger.error("删除模板失败: ${e.message}", e)
                    when (e) {
                        is IllegalArgumentException -> ResponseEntity.ok(ApiResponse.error(ErrorCode.PARAM_ERROR, e.message, messageSource))
                        is IllegalStateException -> ResponseEntity.ok(ApiResponse.error(ErrorCode.BUSINESS_ERROR, e.message, messageSource))
                        else -> ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_TEMPLATE_DELETE_FAILED, e.message, messageSource))
                    }
                }
            )
        } catch (e: Exception) {
            logger.error("删除模板异常: ${e.message}", e)
            ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_TEMPLATE_DELETE_FAILED, e.message, messageSource))
        }
    }
    
    @PostMapping("/copy")
    fun copyTemplate(@RequestBody request: TemplateCopyRequest): ResponseEntity<ApiResponse<TemplateDto>> {
        return try {
            if (request.templateId <= 0) {
                return ResponseEntity.ok(ApiResponse.error(ErrorCode.PARAM_TEMPLATE_ID_INVALID, messageSource = messageSource))
            }
            if (request.templateName.isBlank()) {
                return ResponseEntity.ok(ApiResponse.error(ErrorCode.PARAM_TEMPLATE_NAME_EMPTY, messageSource = messageSource))
            }
            
            val result = templateService.copyTemplate(request)
            result.fold(
                onSuccess = { template ->
                    ResponseEntity.ok(ApiResponse.success(template))
                },
                onFailure = { e ->
                    logger.error("复制模板失败: ${e.message}", e)
                    when (e) {
                        is IllegalArgumentException -> ResponseEntity.ok(ApiResponse.error(ErrorCode.PARAM_ERROR, e.message, messageSource))
                        else -> ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_TEMPLATE_COPY_FAILED, e.message, messageSource))
                    }
                }
            )
        } catch (e: Exception) {
            logger.error("复制模板异常: ${e.message}", e)
            ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_TEMPLATE_COPY_FAILED, e.message, messageSource))
        }
    }
    
    @PostMapping("/list")
    fun getTemplateList(): ResponseEntity<ApiResponse<TemplateListResponse>> {
        return try {
            val result = templateService.getTemplateList()
            result.fold(
                onSuccess = { response ->
                    ResponseEntity.ok(ApiResponse.success(response))
                },
                onFailure = { e ->
                    logger.error("查询模板列表失败: ${e.message}", e)
                    ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_TEMPLATE_LIST_FETCH_FAILED, e.message, messageSource))
                }
            )
        } catch (e: Exception) {
            logger.error("查询模板列表异常: ${e.message}", e)
            ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_TEMPLATE_LIST_FETCH_FAILED, e.message, messageSource))
        }
    }
    
    @PostMapping("/detail")
    fun getTemplateDetail(@RequestBody request: TemplateDetailRequest): ResponseEntity<ApiResponse<TemplateDto>> {
        return try {
            if (request.templateId <= 0) {
                return ResponseEntity.ok(ApiResponse.error(ErrorCode.PARAM_TEMPLATE_ID_INVALID, messageSource = messageSource))
            }
            
            val result = templateService.getTemplateDetail(request.templateId)
            result.fold(
                onSuccess = { template ->
                    ResponseEntity.ok(ApiResponse.success(template))
                },
                onFailure = { e ->
                    logger.error("查询模板详情失败: ${e.message}", e)
                    when (e) {
                        is IllegalArgumentException -> ResponseEntity.ok(ApiResponse.error(ErrorCode.PARAM_ERROR, e.message, messageSource))
                        else -> ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_TEMPLATE_DETAIL_FETCH_FAILED, e.message, messageSource))
                    }
                }
            )
        } catch (e: Exception) {
            logger.error("查询模板详情异常: ${e.message}", e)
            ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_TEMPLATE_DETAIL_FETCH_FAILED, e.message, messageSource))
        }
    }
}

