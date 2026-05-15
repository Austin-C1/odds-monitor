package com.wrbug.polymarketbot.service.system

import com.wrbug.polymarketbot.dto.HttpProxyConfigRequest
import com.wrbug.polymarketbot.dto.ProxyCheckResponse
import com.wrbug.polymarketbot.dto.ProxyConfigDto
import com.wrbug.polymarketbot.entity.ProxyConfig
import com.wrbug.polymarketbot.repository.ProxyConfigRepository
import com.wrbug.polymarketbot.util.ProxyConfigProvider
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

@Service
class ProxyConfigService(
    private val proxyConfigRepository: ProxyConfigRepository
) {
    private val logger = LoggerFactory.getLogger(ProxyConfigService::class.java)

    fun initProxyConfig() {
        ProxyConfigProvider.setProxyConfig(proxyConfigRepository.findByEnabledTrue())
    }

    fun getProxyConfig(): ProxyConfigDto? {
        val config = proxyConfigRepository.findByType("HTTP") ?: return null
        ProxyConfigProvider.setProxyConfig(config.takeIf { it.enabled })
        return toDto(config)
    }

    fun getAllProxyConfigs(): List<ProxyConfigDto> = proxyConfigRepository.findAll().map(::toDto)

    @Transactional
    fun saveHttpProxyConfig(request: HttpProxyConfigRequest): Result<ProxyConfigDto> {
        if (request.host.isBlank()) {
            return Result.failure(IllegalArgumentException("proxy host is required"))
        }
        if (request.port !in 1..65535) {
            return Result.failure(IllegalArgumentException("proxy port must be between 1 and 65535"))
        }

        return runCatching {
            val existing = proxyConfigRepository.findByType("HTTP")
            val config = existing?.copy(
                enabled = request.enabled,
                host = request.host,
                port = request.port,
                username = request.username?.takeIf { it.isNotBlank() },
                password = request.password?.takeIf { it.isNotBlank() } ?: existing.password,
                updatedAt = System.currentTimeMillis()
            ) ?: ProxyConfig(
                type = "HTTP",
                enabled = request.enabled,
                host = request.host,
                port = request.port,
                username = request.username?.takeIf { it.isNotBlank() },
                password = request.password?.takeIf { it.isNotBlank() }
            )

            val saved = proxyConfigRepository.save(config)
            ProxyConfigProvider.setProxyConfig(saved.takeIf { it.enabled })
            toDto(saved)
        }
    }

    fun checkProxy(): ProxyCheckResponse {
        return try {
            val config = proxyConfigRepository.findByEnabledTrue()
                ?: return ProxyCheckResponse.create(false, "proxy is not configured or disabled")
            if (config.type != "HTTP" || config.host.isNullOrBlank() || config.port == null) {
                return ProxyCheckResponse.create(false, "HTTP proxy config is incomplete")
            }

            val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress(config.host, config.port))
            val clientBuilder = OkHttpClient.Builder()
                .proxy(proxy)
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
            if (!config.username.isNullOrBlank() && !config.password.isNullOrBlank()) {
                clientBuilder.proxyAuthenticator { _, response ->
                    response.request.newBuilder()
                        .header("Proxy-Authorization", Credentials.basic(config.username, config.password))
                        .build()
                }
            }

            val startedAt = System.currentTimeMillis()
            clientBuilder.build().newCall(
                Request.Builder().url("https://example.com/").get().build()
            ).execute().use { response ->
                ProxyCheckResponse.create(
                    success = response.isSuccessful,
                    message = if (response.isSuccessful) "proxy connection succeeded" else "proxy HTTP ${response.code}",
                    responseTime = System.currentTimeMillis() - startedAt
                )
            }
        } catch (ex: Exception) {
            logger.warn("proxy check failed: {}", ex.message)
            ProxyCheckResponse.create(false, "proxy check failed: ${ex.message}")
        }
    }

    @Transactional
    fun deleteProxyConfig(id: Long): Result<Unit> = runCatching {
        val config = proxyConfigRepository.findById(id).orElseThrow {
            IllegalArgumentException("proxy config not found")
        }
        val wasEnabled = config.enabled
        proxyConfigRepository.delete(config)
        if (wasEnabled) {
            ProxyConfigProvider.setProxyConfig(null)
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
