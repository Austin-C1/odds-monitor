package com.wrbug.polymarketbot.service.system

import com.wrbug.polymarketbot.api.EthereumRpcApi
import com.wrbug.polymarketbot.api.JsonRpcRequest
import com.wrbug.polymarketbot.entity.NodeHealthStatus
import com.wrbug.polymarketbot.entity.RpcNodeConfig
import com.wrbug.polymarketbot.entity.RpcProviderType
import com.wrbug.polymarketbot.repository.RpcNodeConfigRepository
import com.wrbug.polymarketbot.util.CryptoUtils
import com.wrbug.polymarketbot.util.RetrofitFactory
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class RpcNodeService(
    private val rpcNodeConfigRepository: RpcNodeConfigRepository,
    private val cryptoUtils: CryptoUtils,
    private val retrofitFactory: RetrofitFactory
) {
    
    private val logger = LoggerFactory.getLogger(RpcNodeService::class.java)
    
    companion object {
        private const val DEFAULT_RPC_URL = "https://polygon.publicnode.com"
        private const val DEFAULT_WS_URL = "wss://polygon.publicnode.com"
        private val PROVIDER_HTTP_TEMPLATES = mapOf(
            RpcProviderType.ALCHEMY to "https://polygon-mainnet.g.alchemy.com/v2/{apiKey}",
            RpcProviderType.INFURA to "https://polygon-mainnet.infura.io/v3/{apiKey}",
            RpcProviderType.QUICKNODE to "https://your-endpoint.quiknode.pro/{apiKey}/",
            RpcProviderType.CHAINSTACK to "https://polygon-mainnet.core.chainstack.com/{apiKey}",
            RpcProviderType.GETBLOCK to "https://go.getblock.io/{apiKey}/"
        )
        
        private val PROVIDER_WS_TEMPLATES = mapOf(
            RpcProviderType.ALCHEMY to "wss://polygon-mainnet.g.alchemy.com/v2/{apiKey}",
            RpcProviderType.INFURA to "wss://polygon-mainnet.infura.io/ws/v3/{apiKey}",
            RpcProviderType.QUICKNODE to "wss://your-endpoint.quiknode.pro/{apiKey}/",
            RpcProviderType.CHAINSTACK to "wss://ws-polygon-mainnet.core.chainstack.com/{apiKey}",
            RpcProviderType.GETBLOCK to "wss://go.getblock.io/{apiKey}/"
        )
    }
    
    fun getAllNodes(): List<RpcNodeConfig> {
        val allNodes = rpcNodeConfigRepository.findAllByOrderByPriorityAsc()
        return allNodes.filterNot { isDefaultNode(it) }
    }
    
    fun getAllNodesWithDefault(): List<RpcNodeConfig> {
        val allNodes = rpcNodeConfigRepository.findAllByEnabledTrueOrderByPriorityAsc()
        val (defaultNodes, userNodes) = allNodes.partition { isDefaultNode(it) }
        return userNodes + defaultNodes
    }
    
    private fun isDefaultNode(node: RpcNodeConfig): Boolean {
        return node.httpUrl == DEFAULT_RPC_URL || 
               node.httpUrl == DEFAULT_RPC_URL.removeSuffix("/") ||
               (node.providerType == RpcProviderType.PUBLIC.name && 
                (node.httpUrl.contains("polygon.publicnode.com") || 
                 node.httpUrl.contains("publicnode.com")))
    }
    
    fun getAvailableNode(): Result<RpcNodeConfig> {
        return try {
            val nodes = rpcNodeConfigRepository.findAllByEnabledTrueOrderByPriorityAsc()
                .filterNot { isDefaultNode(it) }
            
            if (nodes.isEmpty()) {
                logger.warn("没有配置任何启用的 RPC 节点，将使用默认节点")
                return Result.success(createDefaultNodeConfig())
            }
            val healthyNodes = nodes.filter { 
                it.lastCheckStatus == NodeHealthStatus.HEALTHY.name 
            }
            for (node in healthyNodes) {
                try {
                    val checkResult = validateNode(node.httpUrl, node.wsUrl).getOrNull()
                    if (checkResult != null && checkResult.status == NodeHealthStatus.HEALTHY) {
                        logger.debug("使用健康的 RPC 节点: ${node.name} (${node.httpUrl})")
                        return Result.success(node)
                    }
                } catch (e: Exception) {
                    logger.debug("节点 ${node.name} 验证失败，尝试下一个节点: ${e.message}")
                }
            }
            for (node in nodes) {
                try {
                    val checkResult = validateNode(node.httpUrl, node.wsUrl).getOrNull()
                    if (checkResult != null && checkResult.status == NodeHealthStatus.HEALTHY) {
                        logger.info("找到可用的 RPC 节点: ${node.name} (${node.httpUrl})")
                        return Result.success(node)
                    }
                } catch (e: Exception) {
                    logger.debug("节点 ${node.name} 验证失败，尝试下一个节点: ${e.message}")
                }
            }
            logger.warn("所有启用的 RPC 节点都不可用，将使用默认节点: $DEFAULT_RPC_URL")
            Result.success(createDefaultNodeConfig())
        } catch (e: Exception) {
            logger.error("获取可用节点失败: ${e.message}", e)
            logger.warn("获取可用节点出现异常，使用默认节点作为兜底")
            Result.success(createDefaultNodeConfig())
        }
    }
    
    private fun createDefaultNodeConfig(): RpcNodeConfig {
        return RpcNodeConfig(
            id = 0L,
            providerType = RpcProviderType.PUBLIC.name,
            name = "默认节点",
            httpUrl = DEFAULT_RPC_URL,
            wsUrl = DEFAULT_WS_URL,
            apiKey = null,
            enabled = true,
            priority = 9999,
            lastCheckTime = System.currentTimeMillis(),
            lastCheckStatus = NodeHealthStatus.HEALTHY.name,
            responseTimeMs = null,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
    }
    
    fun getHttpUrl(): String {
        val node = getAvailableNode().getOrNull()
        return node?.httpUrl ?: DEFAULT_RPC_URL
    }
    
    fun getWsUrl(): String {
        val node = getAvailableNode().getOrNull()
        return node?.wsUrl ?: DEFAULT_WS_URL
    }
    
    @Transactional
    fun addNode(request: AddRpcNodeRequest): Result<RpcNodeConfig> {
        return try {
            val providerType = try {
                RpcProviderType.valueOf(request.providerType.uppercase())
            } catch (e: IllegalArgumentException) {
                return Result.failure(IllegalArgumentException("不支持的服务商类型: ${request.providerType}"))
            }
            val (httpUrl, wsUrl) = if (providerType == RpcProviderType.CUSTOM) {
                if (request.httpUrl.isNullOrBlank()) {
                    return Result.failure(IllegalArgumentException("自定义节点必须提供 HTTP URL"))
                }
                Pair(request.httpUrl, request.wsUrl)
            } else {
                if (request.apiKey.isNullOrBlank()) {
                    return Result.failure(IllegalArgumentException("${request.providerType} 节点必须提供 API Key"))
                }
                val httpTemplate = PROVIDER_HTTP_TEMPLATES[providerType]
                    ?: return Result.failure(IllegalArgumentException("未找到 ${request.providerType} 的 HTTP URL 模板"))
                val wsTemplate = PROVIDER_WS_TEMPLATES[providerType]
                Pair(
                    httpTemplate.replace("{apiKey}", request.apiKey),
                    wsTemplate?.replace("{apiKey}", request.apiKey)
                )
            }
            val validationResult = validateNode(httpUrl, wsUrl)
            if (validationResult.isFailure) {
                return Result.failure(validationResult.exceptionOrNull() ?: Exception("节点验证失败"))
            }
            
            val checkResult = validationResult.getOrNull()!!
            if (checkResult.status != NodeHealthStatus.HEALTHY) {
                return Result.failure(IllegalArgumentException("节点不可用: ${checkResult.message}"))
            }
            val encryptedApiKey = request.apiKey?.let { cryptoUtils.encrypt(it) }
            val maxPriority = rpcNodeConfigRepository.findAllByOrderByPriorityAsc()
                .maxOfOrNull { it.priority } ?: 0
            val node = RpcNodeConfig(
                providerType = providerType.name,
                name = request.name,
                httpUrl = httpUrl,
                wsUrl = wsUrl,
                apiKey = encryptedApiKey,
                enabled = true,
                priority = maxPriority + 1,
                lastCheckTime = checkResult.checkTime,
                lastCheckStatus = checkResult.status.name,
                responseTimeMs = checkResult.responseTimeMs
            )
            
            val savedNode = rpcNodeConfigRepository.save(node)
            logger.info("成功添加 RPC 节点: ${savedNode.name} (${savedNode.httpUrl})")
            Result.success(savedNode)
        } catch (e: Exception) {
            logger.error("添加节点失败: ${e.message}", e)
            Result.failure(e)
        }
    }
    
    @Transactional
    fun updateNode(request: UpdateRpcNodeRequest): Result<RpcNodeConfig> {
        return try {
            val node = rpcNodeConfigRepository.findById(request.id).orElse(null)
                ?: return Result.failure(IllegalArgumentException("节点不存在: ${request.id}"))
            if (isDefaultNode(node)) {
                return Result.failure(IllegalArgumentException("默认节点不允许更新"))
            }
            val isDisabling = request.enabled == false && node.enabled == true
            if (isDisabling) {
                logger.info("节点被禁用，清理 RPC 缓存: ${node.httpUrl}")
                retrofitFactory.clearRpcApiCache(node.httpUrl)
            }
            val updatedNode = node.copy(
                name = request.name ?: node.name,
                enabled = request.enabled ?: node.enabled,
                priority = request.priority ?: node.priority,
                updatedAt = System.currentTimeMillis()
            )
            
            val savedNode = rpcNodeConfigRepository.save(updatedNode)
            logger.info("成功更新 RPC 节点: ${savedNode.name}, enabled=${savedNode.enabled}")
            Result.success(savedNode)
        } catch (e: Exception) {
            logger.error("更新节点失败: ${e.message}", e)
            Result.failure(e)
        }
    }
    
    @Transactional
    fun deleteNode(id: Long): Result<Unit> {
        return try {
            val node = rpcNodeConfigRepository.findById(id).orElse(null)
                ?: return Result.failure(IllegalArgumentException("节点不存在: $id"))
            if (isDefaultNode(node)) {
                return Result.failure(IllegalArgumentException("默认节点不允许删除"))
            }
            logger.info("删除节点，清理 RPC 缓存: ${node.httpUrl}")
            retrofitFactory.clearRpcApiCache(node.httpUrl)
            
            rpcNodeConfigRepository.delete(node)
            logger.info("成功删除 RPC 节点: ${node.name}")
            Result.success(Unit)
        } catch (e: Exception) {
            logger.error("删除节点失败: ${e.message}", e)
            Result.failure(e)
        }
    }
    
    @Transactional
    fun updatePriority(id: Long, priority: Int): Result<Unit> {
        return try {
            val node = rpcNodeConfigRepository.findById(id).orElse(null)
                ?: return Result.failure(IllegalArgumentException("节点不存在: $id"))
            if (isDefaultNode(node)) {
                return Result.failure(IllegalArgumentException("默认节点不允许更新优先级"))
            }
            
            val updatedNode = node.copy(
                priority = priority,
                updatedAt = System.currentTimeMillis()
            )
            
            rpcNodeConfigRepository.save(updatedNode)
            logger.info("成功更新节点优先级: ${node.name} -> $priority")
            Result.success(Unit)
        } catch (e: Exception) {
            logger.error("更新节点优先级失败: ${e.message}", e)
            Result.failure(e)
        }
    }
    
    @Transactional
    fun checkNodeHealth(nodeId: Long): Result<NodeCheckResult> {
        return try {
            val node = rpcNodeConfigRepository.findById(nodeId).orElse(null)
                ?: return Result.failure(IllegalArgumentException("节点不存在: $nodeId"))
            if (isDefaultNode(node)) {
                return Result.failure(IllegalArgumentException("默认节点不允许检查"))
            }
            
            val checkResult = validateNode(node.httpUrl, node.wsUrl).getOrThrow()
            val updatedNode = node.copy(
                lastCheckTime = checkResult.checkTime,
                lastCheckStatus = checkResult.status.name,
                responseTimeMs = checkResult.responseTimeMs,
                updatedAt = System.currentTimeMillis()
            )
            
            rpcNodeConfigRepository.save(updatedNode)
            logger.info("检查节点健康状态: ${node.name} -> ${checkResult.status}")
            Result.success(checkResult)
        } catch (e: Exception) {
            logger.error("检查节点健康状态失败: ${e.message}", e)
            Result.failure(e)
        }
    }
    
    @Transactional
    fun checkAllNodesHealth(): Result<Map<Long, NodeCheckResult>> {
        return try {
            val allNodes = rpcNodeConfigRepository.findAllByEnabledTrueOrderByPriorityAsc()
            val nodes = allNodes.filterNot { isDefaultNode(it) }
            val results = mutableMapOf<Long, NodeCheckResult>()
            
            for (node in nodes) {
                try {
                    val checkResult = validateNode(node.httpUrl, node.wsUrl).getOrNull()
                    if (checkResult != null) {
                        results[node.id!!] = checkResult
                        val updatedNode = node.copy(
                            lastCheckTime = checkResult.checkTime,
                            lastCheckStatus = checkResult.status.name,
                            responseTimeMs = checkResult.responseTimeMs,
                            updatedAt = System.currentTimeMillis()
                        )
                        rpcNodeConfigRepository.save(updatedNode)
                    }
                } catch (e: Exception) {
                    logger.error("检查节点 ${node.name} 失败: ${e.message}", e)
                }
            }
            
            Result.success(results)
        } catch (e: Exception) {
            logger.error("批量检查节点健康状态失败: ${e.message}", e)
            Result.failure(e)
        }
    }
    
    private fun validateNode(httpUrl: String, wsUrl: String?): Result<NodeCheckResult> {
        return try {
            logger.debug("开始验证节点: $httpUrl")
            val rpcApi = retrofitFactory.createEthereumRpcApi(httpUrl)
            val startTime = System.currentTimeMillis()
            val rpcRequest = JsonRpcRequest(
                method = "eth_blockNumber",
                params = emptyList()
            )
            
            val response = kotlinx.coroutines.runBlocking { rpcApi.call(rpcRequest) }
            val responseTime = (System.currentTimeMillis() - startTime).toInt()
            
            if (!response.isSuccessful || response.body() == null) {
                logger.warn("节点验证失败: HTTP ${response.code()}")
                return Result.success(NodeCheckResult(
                    status = NodeHealthStatus.UNHEALTHY,
                    message = "HTTP 请求失败: ${response.code()}",
                    checkTime = System.currentTimeMillis(),
                    responseTimeMs = responseTime
                ))
            }
            
            val rpcResponse = response.body()!!
            if (rpcResponse.error != null) {
                logger.warn("节点验证失败: RPC 错误 ${rpcResponse.error.message}")
                return Result.success(NodeCheckResult(
                    status = NodeHealthStatus.UNHEALTHY,
                    message = "RPC 错误: ${rpcResponse.error.message}",
                    checkTime = System.currentTimeMillis(),
                    responseTimeMs = responseTime
                ))
            }
            
            val blockNumber = rpcResponse.result?.asString
            if (blockNumber.isNullOrBlank()) {
                return Result.success(NodeCheckResult(
                    status = NodeHealthStatus.UNHEALTHY,
                    message = "区块号为空",
                    checkTime = System.currentTimeMillis(),
                    responseTimeMs = responseTime
                ))
            }
            
            logger.info("节点验证成功: $httpUrl, 区块号: $blockNumber, 响应时间: ${responseTime}ms")
            Result.success(NodeCheckResult(
                status = NodeHealthStatus.HEALTHY,
                message = "节点可用, 当前区块: $blockNumber",
                checkTime = System.currentTimeMillis(),
                responseTimeMs = responseTime,
                blockNumber = blockNumber
            ))
        } catch (e: Exception) {
            logger.error("验证节点失败: ${e.message}", e)
            Result.success(NodeCheckResult(
                status = NodeHealthStatus.UNHEALTHY,
                message = "验证失败: ${e.message}",
                checkTime = System.currentTimeMillis(),
                responseTimeMs = null
            ))
        }
    }
}

data class AddRpcNodeRequest(
    val providerType: String,  // ALCHEMY, INFURA, QUICKNODE, CHAINSTACK, GETBLOCK, CUSTOM, PUBLIC
    val name: String,
    val apiKey: String? = null,
    val httpUrl: String? = null,
    val wsUrl: String? = null
)

data class UpdateRpcNodeRequest(
    val id: Long,
    val name: String? = null,
    val enabled: Boolean? = null,
    val priority: Int? = null
)

data class NodeCheckResult(
    val status: NodeHealthStatus,
    val message: String,
    val checkTime: Long,
    val responseTimeMs: Int?,
    val blockNumber: String? = null
)
