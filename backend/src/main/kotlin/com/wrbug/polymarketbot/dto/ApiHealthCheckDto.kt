package com.wrbug.polymarketbot.dto

data class ApiHealthCheckDto(
    val name: String,
    val url: String,  // API URL
    val status: String,
    val message: String,
    val responseTime: Long? = null
)

data class ApiHealthCheckResponse(
    val apis: List<ApiHealthCheckDto>
)

