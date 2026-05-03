package com.wrbug.polymarketbot.dto

data class TemplateCreateRequest(
    val templateName: String,
    val copyMode: String = "RATIO",
    val copyRatio: String? = null,
    val fixedAmount: String? = null,
    val maxOrderSize: String? = null,
    val minOrderSize: String? = null,
    val maxDailyLoss: String? = null,
    val maxDailyOrders: Int? = null,
    val priceTolerance: String? = null,
    val delaySeconds: Int? = null,
    val pollIntervalSeconds: Int? = null,
    val useWebSocket: Boolean? = null,
    val websocketReconnectInterval: Int? = null,
    val websocketMaxRetries: Int? = null,
    val supportSell: Boolean? = null,
    val minOrderDepth: String? = null,
    val maxSpread: String? = null,
    val minPrice: String? = null,
    val maxPrice: String? = null,
    val pushFilteredOrders: Boolean? = null
)

data class TemplateUpdateRequest(
    val templateId: Long,
    val templateName: String? = null,
    val copyMode: String? = null,
    val copyRatio: String? = null,
    val fixedAmount: String? = null,
    val maxOrderSize: String? = null,
    val minOrderSize: String? = null,
    val maxDailyLoss: String? = null,
    val maxDailyOrders: Int? = null,
    val priceTolerance: String? = null,
    val delaySeconds: Int? = null,
    val pollIntervalSeconds: Int? = null,
    val useWebSocket: Boolean? = null,
    val websocketReconnectInterval: Int? = null,
    val websocketMaxRetries: Int? = null,
    val supportSell: Boolean? = null,
    val minOrderDepth: String? = null,
    val maxSpread: String? = null,
    val minPrice: String? = null,
    val maxPrice: String? = null,
    val pushFilteredOrders: Boolean? = null
)

data class TemplateDeleteRequest(
    val templateId: Long
)

data class TemplateCopyRequest(
    val templateId: Long,
    val templateName: String,
    val copyMode: String? = null,
    val copyRatio: String? = null,
    val fixedAmount: String? = null,
    val maxOrderSize: String? = null,
    val minOrderSize: String? = null,
    val maxDailyLoss: String? = null,
    val maxDailyOrders: Int? = null,
    val priceTolerance: String? = null,
    val delaySeconds: Int? = null,
    val pollIntervalSeconds: Int? = null,
    val useWebSocket: Boolean? = null,
    val websocketReconnectInterval: Int? = null,
    val websocketMaxRetries: Int? = null,
    val supportSell: Boolean? = null,
    val minOrderDepth: String? = null,
    val maxSpread: String? = null,
    val minPrice: String? = null,
    val maxPrice: String? = null,
    val pushFilteredOrders: Boolean? = null
)

data class TemplateDetailRequest(
    val templateId: Long
)

data class TemplateDto(
    val id: Long,
    val templateName: String,
    val copyMode: String,
    val copyRatio: String,
    val fixedAmount: String?,
    val maxOrderSize: String,
    val minOrderSize: String,
    val maxDailyLoss: String,
    val maxDailyOrders: Int,
    val priceTolerance: String,
    val delaySeconds: Int,
    val pollIntervalSeconds: Int,
    val useWebSocket: Boolean,
    val websocketReconnectInterval: Int,
    val websocketMaxRetries: Int,
    val supportSell: Boolean,
    val minOrderDepth: String?,
    val maxSpread: String?,
    val minPrice: String?,
    val maxPrice: String?,
    val pushFilteredOrders: Boolean,
    val createdAt: Long,
    val updatedAt: Long
)

data class TemplateListResponse(
    val list: List<TemplateDto>,
    val total: Long
)

