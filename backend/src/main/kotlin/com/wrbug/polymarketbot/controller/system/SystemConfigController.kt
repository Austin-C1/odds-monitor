package com.wrbug.polymarketbot.controller.system

import com.wrbug.polymarketbot.dto.ApiResponse
import com.wrbug.polymarketbot.dto.LiveObservationMinutesUpdateRequest
import com.wrbug.polymarketbot.dto.SystemConfigDto
import com.wrbug.polymarketbot.enums.ErrorCode
import com.wrbug.polymarketbot.service.system.SystemConfigService
import org.slf4j.LoggerFactory
import org.springframework.context.MessageSource
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/system/config")
class SystemConfigController(
    private val systemConfigService: SystemConfigService,
    private val messageSource: MessageSource
) {
    private val logger = LoggerFactory.getLogger(SystemConfigController::class.java)

    @PostMapping("/get")
    fun getSystemConfig(): ResponseEntity<ApiResponse<SystemConfigDto>> {
        return try {
            ResponseEntity.ok(ApiResponse.success(systemConfigService.getSystemConfig()))
        } catch (ex: Exception) {
            logger.error("Failed to get system config: {}", ex.message, ex)
            ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_ERROR, "Failed to get system config", messageSource))
        }
    }

    @PostMapping("/live-observation-minutes/update")
    fun updateLiveObservationMinutes(
        @RequestBody request: LiveObservationMinutesUpdateRequest
    ): ResponseEntity<ApiResponse<SystemConfigDto>> {
        return try {
            val result = systemConfigService.updateLiveObservationMinutes(request.liveObservationMinutes)
            result.fold(
                onSuccess = { config -> ResponseEntity.ok(ApiResponse.success(config)) },
                onFailure = { ex ->
                    logger.error("Failed to update live observation minutes: {}", ex.message, ex)
                    ResponseEntity.ok(
                        ApiResponse.error(
                            ErrorCode.SERVER_ERROR,
                            "Failed to update live observation minutes: ${ex.message}",
                            messageSource
                        )
                    )
                }
            )
        } catch (ex: Exception) {
            logger.error("Failed to update live observation minutes: {}", ex.message, ex)
            ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_ERROR, "Failed to update live observation minutes", messageSource))
        }
    }
}
