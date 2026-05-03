package com.wrbug.polymarketbot.service.system

import com.wrbug.polymarketbot.constants.PolymarketConstants
import com.wrbug.polymarketbot.dto.ApiHealthCheckDto
import com.wrbug.polymarketbot.dto.ApiHealthCheckResponse
import com.wrbug.polymarketbot.util.createClient
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.slf4j.LoggerFactory
import org.springframework.beans.BeansException
import org.springframework.context.ApplicationContext
import org.springframework.context.ApplicationContextAware
import com.wrbug.polymarketbot.service.copytrading.orders.OrderPushService
import com.wrbug.polymarketbot.service.copytrading.monitor.PolymarketActivityWsService
import com.wrbug.polymarketbot.service.copytrading.monitor.UnifiedOnChainWsService
import com.wrbug.polymarketbot.service.binance.BinanceKlineService
import org.springframework.stereotype.Service
import java.util.concurrent.TimeUnit

@Service
class ApiHealthCheckService(
    private val rpcNodeService: RpcNodeService,
    private val clientBuilderFactory: () -> OkHttpClient.Builder = { createClient() }
) : ApplicationContextAware {

    private var applicationContext: ApplicationContext? = null

    override fun setApplicationContext(applicationContext: ApplicationContext) {
        this.applicationContext = applicationContext
    }

    private fun getOrderPushService(): OrderPushService? {
        return try {
            applicationContext?.getBean(OrderPushService::class.java)
        } catch (e: BeansException) {
            null
        }
    }

    private fun getRelayClientService(): RelayClientService? {
        return try {
            applicationContext?.getBean(RelayClientService::class.java)
        } catch (e: BeansException) {
            null
        }
    }

    private fun getPolymarketActivityWsService(): PolymarketActivityWsService? {
        return try {
            applicationContext?.getBean(PolymarketActivityWsService::class.java)
        } catch (e: BeansException) {
            null
        }
    }

    private fun getUnifiedOnChainWsService(): UnifiedOnChainWsService? {
        return try {
            applicationContext?.getBean(UnifiedOnChainWsService::class.java)
        } catch (e: BeansException) {
            null
        }
    }

    private fun getBinanceKlineService(): BinanceKlineService? {
        return try {
            applicationContext?.getBean(BinanceKlineService::class.java)
        } catch (e: BeansException) {
            null
        }
    }

    private val logger = LoggerFactory.getLogger(ApiHealthCheckService::class.java)

    data class HealthCheckTask(
        val name: String,
        val url: String,
        val action: suspend () -> ApiHealthCheckDto
    )

    suspend fun checkAllApis(): ApiHealthCheckResponse {
        return executeChecks(buildHealthChecks())
    }

    internal fun buildHealthChecks(): List<HealthCheckTask> {
        return listOf(
            HealthCheckTask("Polymarket CLOB API", "${PolymarketConstants.CLOB_BASE_URL}/") { checkClobApi() },
            HealthCheckTask("Polymarket Data API", "${PolymarketConstants.DATA_API_BASE_URL}/") { checkDataApi() },
            HealthCheckTask("Polymarket Gamma API", "${PolymarketConstants.GAMMA_BASE_URL}/markets") { checkGammaApi() },
            HealthCheckTask("Polygon RPC", rpcNodeService.getHttpUrl()) { checkPolygonRpc() },
            HealthCheckTask("币安 API", "https://api.binance.com/api/v3/ping") { checkBinanceApi() },
            HealthCheckTask("币安 WebSocket", "wss://stream.binance.com:9443") { checkBinanceWebSocket() },
            HealthCheckTask("Polymarket RTDS WebSocket", "wss://ws-subscriptions-clob.polymarket.com/ws/") { checkPolymarketRtdsWebSocket() },
            HealthCheckTask("Polymarket Activity WebSocket", PolymarketConstants.ACTIVITY_WS_URL) { checkPolymarketActivityWebSocket() },
            HealthCheckTask("链上 WebSocket", rpcNodeService.getWsUrl()) { checkUnifiedOnChainWebSocket() },
            HealthCheckTask("Builder Relayer API", PolymarketConstants.BUILDER_RELAYER_URL) { checkBuilderRelayerApi() }
        )
    }

    internal suspend fun executeChecks(checks: List<HealthCheckTask>): ApiHealthCheckResponse = supervisorScope {
        val apis = checks.map { task ->
            async { executeCheck(task) }
        }.awaitAll()

        ApiHealthCheckResponse(apis = apis)
    }

    private suspend fun executeCheck(task: HealthCheckTask): ApiHealthCheckDto {
        return try {
            task.action()
        } catch (e: Exception) {
            logger.warn("健康检查失败: {}", task.name, e)
            ApiHealthCheckDto(
                name = task.name,
                url = task.url,
                status = "error",
                message = e.message ?: "检查失败"
            )
        }
    }

    private suspend fun checkClobApi(): ApiHealthCheckDto = withContext(Dispatchers.IO) {
        val url = "${PolymarketConstants.CLOB_BASE_URL}/"
        checkApi("Polymarket CLOB API", url)
    }

    private suspend fun checkDataApi(): ApiHealthCheckDto = withContext(Dispatchers.IO) {
        val url = "${PolymarketConstants.DATA_API_BASE_URL}/"
        checkApi("Polymarket Data API", url)
    }

    private suspend fun checkGammaApi(): ApiHealthCheckDto = withContext(Dispatchers.IO) {
        return@withContext try {
            val client = clientBuilderFactory()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .writeTimeout(5, TimeUnit.SECONDS)
                .build()
            val url = "${PolymarketConstants.GAMMA_BASE_URL}/markets"
            val request = Request.Builder()
                .url(url)
                .get()
                .build()

            val startTime = System.currentTimeMillis()
            client.newCall(request).execute().use { response ->
                val responseTime = System.currentTimeMillis() - startTime

                if (response.isSuccessful) {
                val responseBody = response.body?.string()
                if (responseBody != null && (responseBody.trim().startsWith("[") || responseBody.trim()
                        .startsWith("{"))
                ) {
                    ApiHealthCheckDto(
                        name = "Polymarket Gamma API",
                        url = url,
                        status = "success",
                        message = "连接成功",
                        responseTime = responseTime
                    )
                } else {
                    ApiHealthCheckDto(
                        name = "Polymarket Gamma API",
                        url = url,
                        status = "error",
                        message = "响应格式不正确",
                        responseTime = responseTime
                    )
                }
                } else {
                    ApiHealthCheckDto(
                        name = "Polymarket Gamma API",
                        url = url,
                        status = "error",
                        message = "HTTP ${response.code}: ${response.message}",
                        responseTime = responseTime
                    )
                }
            }
        } catch (e: Exception) {
            logger.warn("检查 Polymarket Gamma API 失败", e)
            ApiHealthCheckDto(
                name = "Polymarket Gamma API",
                url = "${PolymarketConstants.GAMMA_BASE_URL}/markets",
                status = "error",
                message = e.message ?: "连接失败"
            )
        }
    }

    private suspend fun checkPolygonRpc(): ApiHealthCheckDto = withContext(Dispatchers.IO) {
        val rpcUrl = rpcNodeService.getHttpUrl()
        checkJsonRpcApi("Polygon RPC", rpcUrl)
    }

    private suspend fun checkBinanceApi(): ApiHealthCheckDto = withContext(Dispatchers.IO) {
        val url = "https://api.binance.com/api/v3/ping"
        checkApi("币安 API", url)
    }

    private suspend fun checkBinanceWebSocket(): ApiHealthCheckDto = withContext(Dispatchers.Default) {
        val binanceWsUrl = "wss://stream.binance.com:9443"
        try {
            val binanceKlineService = getBinanceKlineService()
            if (binanceKlineService == null) {
                return@withContext ApiHealthCheckDto(
                    name = "币安 WebSocket",
                    url = binanceWsUrl,
                    status = "error",
                    message = "服务未初始化"
                )
            }
            val statuses = binanceKlineService.getConnectionStatuses()
            val total = statuses.size
            val connected = statuses.values.count { it }
            if (connected == total && total > 0) {
                ApiHealthCheckDto(
                    name = "币安 WebSocket",
                    url = binanceWsUrl,
                    status = "success",
                    message = "连接正常 (按策略订阅)"
                )
            } else if (total == 0) {
                ApiHealthCheckDto(
                    name = "币安 WebSocket",
                    url = binanceWsUrl,
                    status = "success",
                    message = "无加密价差策略，未订阅"
                )
            } else if (connected > 0) {
                val which = statuses.filter { it.value }.keys.joinToString("、")
                ApiHealthCheckDto(
                    name = "币安 WebSocket",
                    url = binanceWsUrl,
                    status = "error",
                    message = "部分连接正常 ($which)"
                )
            } else {
                ApiHealthCheckDto(
                    name = "币安 WebSocket",
                    url = binanceWsUrl,
                    status = "error",
                    message = "连接断开"
                )
            }
        } catch (e: Exception) {
            logger.warn("检查币安 WebSocket 状态失败", e)
            ApiHealthCheckDto(
                name = "币安 WebSocket",
                url = binanceWsUrl,
                status = "error",
                message = "检查失败：${e.message}"
            )
        }
    }

    private suspend fun checkPolymarketRtdsWebSocket(): ApiHealthCheckDto = withContext(Dispatchers.Default) {
        try {
            val orderPushService = getOrderPushService()
            val statuses = orderPushService?.getConnectionStatuses() ?: emptyMap()
            val total = statuses.size
            val connected = statuses.values.count { it }

            if (total == 0) {
                ApiHealthCheckDto(
                    name = "Polymarket RTDS WebSocket",
                    url = PolymarketConstants.RTDS_WS_URL,
                    status = "skipped",
                    message = "未配置账户连接"
                )
            } else if (connected > 0) {
                val message = if (connected == total) {
                    "所有账户连接正常 ($connected/$total)"
                } else {
                    "部分账户连接正常 ($connected/$total)"
                }
                ApiHealthCheckDto(
                    name = "Polymarket RTDS WebSocket",
                    url = PolymarketConstants.RTDS_WS_URL,
                    status = "success",
                    message = message
                )
            } else {
                ApiHealthCheckDto(
                    name = "Polymarket RTDS WebSocket",
                    url = PolymarketConstants.RTDS_WS_URL,
                    status = "error",
                    message = "所有账户连接断开 (0/$total)"
                )
            }
        } catch (e: Exception) {
            logger.warn("检查 Polymarket RTDS WebSocket 状态失败", e)
            ApiHealthCheckDto(
                name = "Polymarket RTDS WebSocket",
                url = PolymarketConstants.RTDS_WS_URL,
                status = "error",
                message = "检查失败：${e.message}"
            )
        }
    }

    private suspend fun checkPolymarketActivityWebSocket(): ApiHealthCheckDto = withContext(Dispatchers.Default) {
        try {
            val activityWsService = getPolymarketActivityWsService()
            val monitoredCount = activityWsService?.getMonitoredCount() ?: 0
            if (monitoredCount <= 0) {
                return@withContext ApiHealthCheckDto(
                    name = "Polymarket Activity WebSocket",
                    url = PolymarketConstants.ACTIVITY_WS_URL,
                    status = "skipped",
                    message = "未配置 Leader 监听"
                )
            }
            val isConnected = activityWsService?.isConnected() ?: false

            if (isConnected) {
                ApiHealthCheckDto(
                    name = "Polymarket Activity WebSocket",
                    url = PolymarketConstants.ACTIVITY_WS_URL,
                    status = "success",
                    message = "连接正常"
                )
            } else {
                ApiHealthCheckDto(
                    name = "Polymarket Activity WebSocket",
                    url = PolymarketConstants.ACTIVITY_WS_URL,
                    status = "error",
                    message = "连接断开"
                )
            }
        } catch (e: Exception) {
            logger.warn("检查 Polymarket Activity WebSocket 状态失败", e)
            ApiHealthCheckDto(
                name = "Polymarket Activity WebSocket",
                url = PolymarketConstants.ACTIVITY_WS_URL,
                status = "error",
                message = "检查失败：${e.message}"
            )
        }
    }

    private suspend fun checkUnifiedOnChainWebSocket(): ApiHealthCheckDto = withContext(Dispatchers.Default) {
        try {
            val unifiedOnChainWsService = getUnifiedOnChainWsService()

            if (unifiedOnChainWsService == null) {
                return@withContext ApiHealthCheckDto(
                    name = "链上 WebSocket",
                    url = rpcNodeService.getWsUrl(),
                    status = "error",
                    message = "服务未初始化"
                )
            }
            val statuses = unifiedOnChainWsService.getConnectionStatuses()
            val total = statuses.size
            val connected = statuses.values.count { it }

            if (total == 0) {
                ApiHealthCheckDto(
                    name = "链上 WebSocket",
                    url = rpcNodeService.getWsUrl(),
                    status = "skipped",
                    message = "未配置地址监听"
                )
            } else if (connected > 0) {
                val message = if (connected == total) {
                    "所有地址连接正常 ($connected/$total)"
                } else {
                    "部分地址连接正常 ($connected/$total)"
                }
                ApiHealthCheckDto(
                    name = "链上 WebSocket",
                    url = rpcNodeService.getWsUrl(),
                    status = "success",
                    message = message
                )
            } else {
                ApiHealthCheckDto(
                    name = "链上 WebSocket",
                    url = rpcNodeService.getWsUrl(),
                    status = "error",
                    message = "所有地址连接断开 (0/$total)"
                )
            }
        } catch (e: Exception) {
            logger.warn("检查链上 WebSocket 状态失败", e)
            ApiHealthCheckDto(
                name = "链上 WebSocket",
                url = rpcNodeService.getWsUrl(),
                status = "error",
                message = "检查失败：${e.message}"
            )
        }
    }

    private suspend fun checkApi(name: String, url: String): ApiHealthCheckDto {
        return try {
            val client = clientBuilderFactory()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .writeTimeout(5, TimeUnit.SECONDS)
                .build()

            val request = Request.Builder()
                .url(url)
                .get()
                .build()

            val startTime = System.currentTimeMillis()
            client.newCall(request).execute().use { response ->
                val responseTime = System.currentTimeMillis() - startTime

                if (response.isSuccessful) {
                ApiHealthCheckDto(
                    name = name,
                    url = url,
                    status = "success",
                    message = "连接成功",
                    responseTime = responseTime
                )
                } else {
                ApiHealthCheckDto(
                    name = name,
                    url = url,
                    status = "error",
                    message = "HTTP ${response.code}: ${response.message}",
                    responseTime = responseTime
                )
                }
            }
        } catch (e: Exception) {
            logger.warn("检查 API 失败: $name ($url)", e)
            ApiHealthCheckDto(
                name = name,
                url = url,
                status = "error",
                message = e.message ?: "连接失败"
            )
        }
    }

    private suspend fun checkJsonRpcApi(name: String, url: String): ApiHealthCheckDto {
        return try {
            val client = clientBuilderFactory()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .writeTimeout(5, TimeUnit.SECONDS)
                .build()
            val jsonRpcRequest = """
                {
                    "jsonrpc": "2.0",
                    "method": "eth_chainId",
                    "params": [],
                    "id": 1
                }
            """.trimIndent()

            val mediaType = "application/json; charset=utf-8".toMediaTypeOrNull()
                ?: "application/json".toMediaType()

            val request = Request.Builder()
                .url(url)
                .post(okhttp3.RequestBody.create(mediaType, jsonRpcRequest))
                .header("Content-Type", "application/json")
                .build()

            val startTime = System.currentTimeMillis()
            client.newCall(request).execute().use { response ->
                val responseTime = System.currentTimeMillis() - startTime

                if (response.isSuccessful) {
                val responseBody = response.body?.string()
                if (responseBody != null && responseBody.contains("\"result\"")) {
                    ApiHealthCheckDto(
                        name = name,
                        url = url,
                        status = "success",
                        message = "连接成功",
                        responseTime = responseTime
                    )
                } else {
                    ApiHealthCheckDto(
                        name = name,
                        url = url,
                        status = "error",
                        message = "响应格式不正确",
                        responseTime = responseTime
                    )
                }
                } else {
                ApiHealthCheckDto(
                    name = name,
                    url = url,
                    status = "error",
                    message = "HTTP ${response.code}: ${response.message}",
                    responseTime = responseTime
                )
                }
            }
        } catch (e: Exception) {
            logger.warn("检查 JSON-RPC API 失败: $name ($url)", e)
            ApiHealthCheckDto(
                name = name,
                url = url,
                status = "error",
                message = e.message ?: "连接失败"
            )
        }
    }
    
    private suspend fun checkBuilderRelayerApi(): ApiHealthCheckDto = withContext(Dispatchers.IO) {
        val relayClientService = getRelayClientService()
        
        if (relayClientService == null) {
            return@withContext ApiHealthCheckDto(
                name = "Builder Relayer API",
                url = PolymarketConstants.BUILDER_RELAYER_URL,
                status = "error",
                message = "服务未初始化"
            )
        }
        
        if (!relayClientService.isBuilderApiKeyConfigured()) {
            return@withContext ApiHealthCheckDto(
                name = "Builder Relayer API",
                url = PolymarketConstants.BUILDER_RELAYER_URL,
                status = "skipped",
                message = "Builder API Key 未配置"
            )
        }
        
        return@withContext try {
            val result = relayClientService.checkBuilderRelayerApiHealth()
            result.fold(
                onSuccess = { responseTime ->
                    ApiHealthCheckDto(
                        name = "Builder Relayer API",
                        url = PolymarketConstants.BUILDER_RELAYER_URL,
                        status = "success",
                        message = "连接成功",
                        responseTime = responseTime
                    )
                },
                onFailure = { e ->
                    ApiHealthCheckDto(
                        name = "Builder Relayer API",
                        url = PolymarketConstants.BUILDER_RELAYER_URL,
                        status = "error",
                        message = e.message ?: "连接失败"
                    )
                }
            )
        } catch (e: Exception) {
            logger.warn("检查 Builder Relayer API 失败", e)
            ApiHealthCheckDto(
                name = "Builder Relayer API",
                url = PolymarketConstants.BUILDER_RELAYER_URL,
                status = "error",
                message = e.message ?: "连接失败"
            )
        }
    }
    
}

