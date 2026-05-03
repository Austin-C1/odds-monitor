package com.wrbug.polymarketbot.dto

data class ProxyConfigDto(
    val id: Long?,
    val type: String,  // HTTP, CLASH, SS
    val enabled: Boolean,
    val host: String?,
    val port: Int?,
    val username: String?,
    val subscriptionUrl: String?,
    val lastSubscriptionUpdate: Long?,
    val createdAt: Long,
    val updatedAt: Long
)

data class HttpProxyConfigRequest(
    val enabled: Boolean,
    val host: String,
    val port: Int,
    val username: String? = null,
    val password: String? = null
)

data class SubscriptionProxyConfigRequest(
    val enabled: Boolean,
    val subscriptionUrl: String,
    val type: String
)

data class ProxyCheckResponse(
    val success: Boolean,
    val message: String,
    val responseTime: Long? = null,
    val latency: Long? = null
) {
    companion object {
        fun create(success: Boolean, message: String, responseTime: Long? = null): ProxyCheckResponse {
            return ProxyCheckResponse(
                success = success,
                message = message,
                responseTime = responseTime,
                latency = responseTime
            )
        }
    }
}

