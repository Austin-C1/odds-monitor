package com.wrbug.polymarketbot.controller.system

import com.wrbug.polymarketbot.dto.ApiResponse
import com.wrbug.polymarketbot.entity.RpcNodeConfig
import com.wrbug.polymarketbot.enums.ErrorCode
import com.wrbug.polymarketbot.service.system.AddRpcNodeRequest
import com.wrbug.polymarketbot.service.system.NodeCheckResult
import com.wrbug.polymarketbot.service.system.RpcNodeService
import com.wrbug.polymarketbot.service.system.UpdateRpcNodeRequest
import org.slf4j.LoggerFactory
import org.springframework.context.MessageSource
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/system/rpc-nodes")
class RpcNodeController(
    private val rpcNodeService: RpcNodeService,
    private val messageSource: MessageSource
) {
    
    private val logger = LoggerFactory.getLogger(RpcNodeController::class.java)
    
    @PostMapping("/list")
    fun getAllNodes(@RequestBody request: Map<String, Any>?): ResponseEntity<ApiResponse<List<RpcNodeConfigDto>>> {
        return try {
            val nodes = rpcNodeService.getAllNodes()
            val dtos = nodes.map { it.toDto() }
            ResponseEntity.ok(ApiResponse.success(dtos))
        } catch (e: Exception) {
            logger.error("获取 RPC 节点列表失败: ${e.message}", e)
            ResponseEntity.ok(ApiResponse.error(
                ErrorCode.SERVER_ERROR,
                customMsg = "获取节点列表失败：${e.message}",
                messageSource = messageSource
            ))
        }
    }
    
    @PostMapping("/add")
    fun addNode(@RequestBody request: AddRpcNodeRequest): ResponseEntity<ApiResponse<RpcNodeConfigDto>> {
        return try {
            if (request.providerType.isBlank()) {
                return ResponseEntity.ok(ApiResponse.paramError("服务商类型不能为空"))
            }
            if (request.name.isBlank()) {
                return ResponseEntity.ok(ApiResponse.paramError("节点名称不能为空"))
            }
            
            val result = rpcNodeService.addNode(request)
            
            result.fold(
                onSuccess = { node ->
                    ResponseEntity.ok(ApiResponse.success(node.toDto()))
                },
                onFailure = { e ->
                    logger.error("添加 RPC 节点失败: ${e.message}", e)
                    ResponseEntity.ok(ApiResponse.error(
                        ErrorCode.SERVER_ERROR,
                        customMsg = "添加节点失败：${e.message}",
                        messageSource = messageSource
                    ))
                }
            )
        } catch (e: Exception) {
            logger.error("添加 RPC 节点异常: ${e.message}", e)
            ResponseEntity.ok(ApiResponse.error(
                ErrorCode.SERVER_ERROR,
                customMsg = "添加节点失败：${e.message}",
                messageSource = messageSource
            ))
        }
    }
    
    @PostMapping("/update")
    fun updateNode(@RequestBody request: UpdateRpcNodeRequest): ResponseEntity<ApiResponse<RpcNodeConfigDto>> {
        return try {
            val result = rpcNodeService.updateNode(request)
            
            result.fold(
                onSuccess = { node ->
                    ResponseEntity.ok(ApiResponse.success(node.toDto()))
                },
                onFailure = { e ->
                    logger.error("更新 RPC 节点失败: ${e.message}", e)
                    ResponseEntity.ok(ApiResponse.error(
                        ErrorCode.SERVER_ERROR,
                        customMsg = "更新节点失败：${e.message}",
                        messageSource = messageSource
                    ))
                }
            )
        } catch (e: Exception) {
            logger.error("更新 RPC 节点异常: ${e.message}", e)
            ResponseEntity.ok(ApiResponse.error(
                ErrorCode.SERVER_ERROR,
                customMsg = "更新节点失败：${e.message}",
                messageSource = messageSource
            ))
        }
    }
    
    @PostMapping("/delete")
    fun deleteNode(@RequestBody request: DeleteRpcNodeRequest): ResponseEntity<ApiResponse<Unit>> {
        return try {
            val result = rpcNodeService.deleteNode(request.id)
            
            result.fold(
                onSuccess = {
                    ResponseEntity.ok(ApiResponse.success(Unit))
                },
                onFailure = { e ->
                    logger.error("删除 RPC 节点失败: ${e.message}", e)
                    ResponseEntity.ok(ApiResponse.error(
                        ErrorCode.SERVER_ERROR,
                        customMsg = "删除节点失败：${e.message}",
                        messageSource = messageSource
                    ))
                }
            )
        } catch (e: Exception) {
            logger.error("删除 RPC 节点异常: ${e.message}", e)
            ResponseEntity.ok(ApiResponse.error(
                ErrorCode.SERVER_ERROR,
                customMsg = "删除节点失败：${e.message}",
                messageSource = messageSource
            ))
        }
    }
    
    @PostMapping("/update-priority")
    fun updatePriority(@RequestBody request: UpdatePriorityRequest): ResponseEntity<ApiResponse<Unit>> {
        return try {
            val result = rpcNodeService.updatePriority(request.id, request.priority)
            
            result.fold(
                onSuccess = {
                    ResponseEntity.ok(ApiResponse.success(Unit))
                },
                onFailure = { e ->
                    logger.error("更新节点优先级失败: ${e.message}", e)
                    ResponseEntity.ok(ApiResponse.error(
                        ErrorCode.SERVER_ERROR,
                        customMsg = "更新优先级失败：${e.message}",
                        messageSource = messageSource
                    ))
                }
            )
        } catch (e: Exception) {
            logger.error("更新节点优先级异常: ${e.message}", e)
            ResponseEntity.ok(ApiResponse.error(
                ErrorCode.SERVER_ERROR,
                customMsg = "更新优先级失败：${e.message}",
                messageSource = messageSource
            ))
        }
    }
    
    @PostMapping("/check-health")
    fun checkHealth(@RequestBody request: CheckHealthRequest): ResponseEntity<ApiResponse<Any>> {
        return try {
            if (request.id != null) {
                val result = rpcNodeService.checkNodeHealth(request.id)
                result.fold(
                    onSuccess = { checkResult ->
                        ResponseEntity.ok(ApiResponse.success(checkResult.toDto()))
                    },
                    onFailure = { e ->
                        logger.error("检查节点健康状态失败: ${e.message}", e)
                        ResponseEntity.ok(ApiResponse.error(
                            ErrorCode.SERVER_ERROR,
                            customMsg = "检查节点失败：${e.message}",
                            messageSource = messageSource
                        ))
                    }
                )
            } else {
                val result = rpcNodeService.checkAllNodesHealth()
                result.fold(
                    onSuccess = { checkResults ->
                        val dtos = checkResults.mapValues { it.value.toDto() }
                        ResponseEntity.ok(ApiResponse.success(dtos))
                    },
                    onFailure = { e ->
                        logger.error("批量检查节点健康状态失败: ${e.message}", e)
                        ResponseEntity.ok(ApiResponse.error(
                            ErrorCode.SERVER_ERROR,
                            customMsg = "批量检查节点失败：${e.message}",
                            messageSource = messageSource
                        ))
                    }
                )
            }
        } catch (e: Exception) {
            logger.error("检查节点健康状态异常: ${e.message}", e)
            ResponseEntity.ok(ApiResponse.error(
                ErrorCode.SERVER_ERROR,
                customMsg = "检查节点失败：${e.message}",
                messageSource = messageSource
            ))
        }
    }
    
    @PostMapping("/validate")
    fun validateNode(@RequestBody request: AddRpcNodeRequest): ResponseEntity<ApiResponse<ValidateNodeResponse>> {
        return try {
            if (request.providerType.isBlank()) {
                return ResponseEntity.ok(ApiResponse.paramError("服务商类型不能为空"))
            }
            if (request.name.isBlank()) {
                return ResponseEntity.ok(ApiResponse.paramError("节点名称不能为空"))
            }
            val result = rpcNodeService.addNode(request)
            
            result.fold(
                onSuccess = { node ->
                    rpcNodeService.deleteNode(node.id!!)
                    ResponseEntity.ok(ApiResponse.success(ValidateNodeResponse(
                        valid = true,
                        message = "节点可用",
                        responseTimeMs = node.responseTimeMs
                    )))
                },
                onFailure = { e ->
                    ResponseEntity.ok(ApiResponse.success(ValidateNodeResponse(
                        valid = false,
                        message = e.message ?: "节点验证失败",
                        responseTimeMs = null
                    )))
                }
            )
        } catch (e: Exception) {
            logger.error("验证节点异常: ${e.message}", e)
            ResponseEntity.ok(ApiResponse.success(ValidateNodeResponse(
                valid = false,
                message = e.message ?: "节点验证失败",
                responseTimeMs = null
            )))
        }
    }
}

data class RpcNodeConfigDto(
    val id: Long?,
    val providerType: String,
    val name: String,
    val httpUrl: String,
    val wsUrl: String?,
    val apiKeyMasked: String?,
    val enabled: Boolean,
    val priority: Int,
    val lastCheckTime: Long?,
    val lastCheckStatus: String?,
    val responseTimeMs: Int?,
    val createdAt: Long,
    val updatedAt: Long
)

data class NodeCheckResultDto(
    val status: String,
    val message: String,
    val checkTime: Long,
    val responseTimeMs: Int?,
    val blockNumber: String?
)

data class ValidateNodeResponse(
    val valid: Boolean,
    val message: String,
    val responseTimeMs: Int?
)

data class DeleteRpcNodeRequest(
    val id: Long
)

data class UpdatePriorityRequest(
    val id: Long,
    val priority: Int
)

data class CheckHealthRequest(
    val id: Long? = null
)

private fun RpcNodeConfig.toDto(): RpcNodeConfigDto {
    return RpcNodeConfigDto(
        id = id,
        providerType = providerType,
        name = name,
        httpUrl = httpUrl,
        wsUrl = wsUrl,
        apiKeyMasked = apiKey?.let { "***" },
        enabled = enabled,
        priority = priority,
        lastCheckTime = lastCheckTime,
        lastCheckStatus = lastCheckStatus,
        responseTimeMs = responseTimeMs,
        createdAt = createdAt,
        updatedAt = updatedAt
    )
}

private fun NodeCheckResult.toDto(): NodeCheckResultDto {
    return NodeCheckResultDto(
        status = status.name,
        message = message,
        checkTime = checkTime,
        responseTimeMs = responseTimeMs,
        blockNumber = blockNumber
    )
}
