package com.wrbug.polymarketbot.service.system

import com.wrbug.polymarketbot.dto.*
import com.wrbug.polymarketbot.entity.ProxyConfig
import com.wrbug.polymarketbot.repository.ProxyConfigRepository
import com.wrbug.polymarketbot.service.copytrading.monitor.CopyTradingWebSocketService
import com.wrbug.polymarketbot.service.copytrading.orders.OrderPushService
import com.wrbug.polymarketbot.util.ProxyConfigProvider
import okhttp3.*
import org.slf4j.LoggerFactory
import org.springframework.beans.BeansException
import org.springframework.context.ApplicationContext
import org.springframework.context.ApplicationContextAware
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

@Service
class ProxyConfigService(
    private val proxyConfigRepository: ProxyConfigRepository
) : ApplicationContextAware {
    
    private var applicationContext: ApplicationContext? = null
    
    override fun setApplicationContext(applicationContext: ApplicationContext) {
        this.applicationContext = applicationContext
    }
    
    private val logger = LoggerFactory.getLogger(ProxyConfigService::class.java)
    
    fun getProxyConfig(): ProxyConfigDto? {
        val config = proxyConfigRepository.findByType("HTTP")
            ?: return null
        if (config.enabled) {
            ProxyConfigProvider.setProxyConfig(config)
        } else {
            ProxyConfigProvider.setProxyConfig(null)
        }
        
        return toDto(config)
    }
    
    fun initProxyConfig() {
        val config = proxyConfigRepository.findByEnabledTrue()
        ProxyConfigProvider.setProxyConfig(config)
        if (config != null) {
            logger.info("初始化代理配置：type=${config.type}, host=${config.host}, port=${config.port}, enabled=${config.enabled}")
        } else {
            logger.info("未找到启用的代理配置")
        }
    }
    
    fun getAllProxyConfigs(): List<ProxyConfigDto> {
        return proxyConfigRepository.findAll().map { toDto(it) }
    }
    
    @Transactional
    fun saveHttpProxyConfig(request: HttpProxyConfigRequest): Result<ProxyConfigDto> {
        return try {
            if (request.host.isBlank()) {
                return Result.failure(IllegalArgumentException("代理主机不能为空"))
            }
            if (request.port <= 0 || request.port > 65535) {
                return Result.failure(IllegalArgumentException("代理端口必须在 1-65535 之间"))
            }
            val existing = proxyConfigRepository.findByType("HTTP")
            
            val config = if (existing != null) {
                val password = if (request.password != null && request.password.isNotBlank()) {
                    request.password
                } else {
                    existing.password
                }
                
                existing.copy(
                    enabled = request.enabled,
                    host = request.host,
                    port = request.port,
                    username = request.username?.takeIf { it.isNotBlank() },
                    password = password,
                    updatedAt = System.currentTimeMillis()
                )
            } else {
                val password = if (request.password != null && request.password.isNotBlank()) {
                    request.password
                } else {
                    null
                }
                
                ProxyConfig(
                    type = "HTTP",
                    enabled = request.enabled,
                    host = request.host,
                    port = request.port,
                    username = request.username?.takeIf { it.isNotBlank() },
                    password = password
                )
            }
            
            val saved = proxyConfigRepository.save(config)
            logger.info("保存 HTTP 代理配置成功：host=${saved.host}, port=${saved.port}, enabled=${saved.enabled}")
            if (saved.enabled) {
                ProxyConfigProvider.setProxyConfig(saved)
            } else {
                ProxyConfigProvider.setProxyConfig(null)
            }
            triggerWebSocketReconnect()
            
            Result.success(toDto(saved))
        } catch (e: Exception) {
            logger.error("保存 HTTP 代理配置失败", e)
            Result.failure(e)
        }
    }
    
    fun checkProxy(): ProxyCheckResponse {
        return try {
            val config = proxyConfigRepository.findByEnabledTrue()
                ?: return ProxyCheckResponse.create(
                    success = false,
                    message = "未配置代理或代理未启用"
                )
            
            if (config.type != "HTTP") {
                return ProxyCheckResponse.create(
                    success = false,
                    message = "当前仅支持检查 HTTP 代理（订阅代理检查功能待实现）"
                )
            }
            
            if (config.host == null || config.port == null) {
                return ProxyCheckResponse.create(
                    success = false,
                    message = "代理配置不完整：缺少主机或端口"
                )
            }
            val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress(config.host, config.port))
            val clientBuilder = OkHttpClient.Builder()
                .proxy(proxy)
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
            if (config.username != null && config.password != null) {
                clientBuilder.proxyAuthenticator { _, response ->
                    val credential = okhttp3.Credentials.basic(config.username, config.password)
                    response.request.newBuilder()
                        .header("Proxy-Authorization", credential)
                        .build()
                }
            }
            
            val client = clientBuilder.build()
            val request = Request.Builder()
                .url("https://data-api.polymarket.com/")
                .get()
                .build()
            
            val startTime = System.currentTimeMillis()
            client.newCall(request).execute().use { response ->
                val responseTime = System.currentTimeMillis() - startTime
            
                val responseBody = response.body?.string()
            
                if (response.isSuccessful && responseBody != null) {
                if (responseBody.contains("\"data\"") && responseBody.contains("OK")) {
                    logger.info("代理检查成功：host=${config.host}, port=${config.port}, responseTime=${responseTime}ms")
                    ProxyCheckResponse.create(
                        success = true,
                        message = "代理连接成功",
                        responseTime = responseTime
                    )
                } else {
                    ProxyCheckResponse.create(
                        success = false,
                        message = "代理连接成功，但响应格式不正确：$responseBody",
                        responseTime = responseTime
                    )
                }
                } else {
                ProxyCheckResponse.create(
                    success = false,
                    message = "代理连接失败：HTTP ${response.code} ${response.message}",
                    responseTime = responseTime
                )
                }
            }
        } catch (e: Exception) {
            logger.error("代理检查异常", e)
            ProxyCheckResponse.create(
                success = false,
                message = "代理检查失败：${e.message}"
            )
        }
    }
    
    @Transactional
    fun deleteProxyConfig(id: Long): Result<Unit> {
        return try {
            val config = proxyConfigRepository.findById(id)
                .orElse(null) ?: return Result.failure(IllegalArgumentException("代理配置不存在"))
            
            val wasEnabled = config.enabled
            proxyConfigRepository.delete(config)
            logger.info("删除代理配置成功：id=$id, type=${config.type}")
            if (wasEnabled) {
                ProxyConfigProvider.setProxyConfig(null)
                triggerWebSocketReconnect()
            }
            
            Result.success(Unit)
        } catch (e: Exception) {
            logger.error("删除代理配置失败：id=$id", e)
            Result.failure(e)
        }
    }
    
    private fun triggerWebSocketReconnect() {
        try {
            val context = applicationContext ?: return
            try {
                val orderPushService = context.getBean(OrderPushService::class.java)
                kotlinx.coroutines.runBlocking {
                    try {
                        orderPushService.reconnectAllAccounts()
                        logger.info("已触发订单推送服务 WebSocket 重连")
                    } catch (e: Exception) {
                        logger.error("触发订单推送服务重连失败", e)
                    }
                }
            } catch (e: BeansException) {
                logger.debug("订单推送服务未找到，跳过重连", e)
            }
            try {
                val copyTradingWebSocketService = context.getBean(CopyTradingWebSocketService::class.java)
                try {
                    copyTradingWebSocketService.reconnectAll()
                    logger.info("已触发跟单 WebSocket 服务重连")
                } catch (e: Exception) {
                    logger.error("触发跟单 WebSocket 服务重连失败", e)
                }
            } catch (e: BeansException) {
                logger.debug("跟单 WebSocket 服务未找到，跳过重连", e)
            }
        } catch (e: Exception) {
            logger.error("触发 WebSocket 重连失败", e)
        }
    }
    
    private fun toDto(config: ProxyConfig): ProxyConfigDto {
        return ProxyConfigDto(
            id = config.id,
            type = config.type,
            enabled = config.enabled,
            host = config.host,
            port = config.port,
            username = config.username,
            subscriptionUrl = config.subscriptionUrl,
            lastSubscriptionUpdate = config.lastSubscriptionUpdate,
            createdAt = config.createdAt,
            updatedAt = config.updatedAt
        )
    }
}

