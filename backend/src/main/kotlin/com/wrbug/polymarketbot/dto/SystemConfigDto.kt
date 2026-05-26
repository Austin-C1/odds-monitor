package com.wrbug.polymarketbot.dto

data class LiveObservationMinutesUpdateRequest(
    val liveObservationMinutes: Int? = null
)

data class AutoBettingEnabledUpdateRequest(
    val autoBettingEnabled: Boolean = false
)

data class SystemConfigDto(
    val liveObservationMinutes: Int? = null,
    val autoBettingEnabled: Boolean = false
)
