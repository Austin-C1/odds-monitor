package com.wrbug.polymarketbot.controller.system

import com.wrbug.polymarketbot.dto.ApiResponse
import com.wrbug.polymarketbot.dto.LargeBetMonitorConfigDto
import com.wrbug.polymarketbot.dto.LargeBetMonitorConfigUpdateRequest
import com.wrbug.polymarketbot.dto.LargeBetMonitorStatusDto
import com.wrbug.polymarketbot.dto.LargeBetWatchRecordDto
import com.wrbug.polymarketbot.enums.ErrorCode
import com.wrbug.polymarketbot.service.largebet.LargeBetActivityMonitorService
import com.wrbug.polymarketbot.service.largebet.LargeBetMonitorConfigService
import com.wrbug.polymarketbot.service.largebet.LargeBetWatchRecordService
import com.wrbug.polymarketbot.service.system.TelegramNotificationService
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/system/large-bet-monitor")
class LargeBetMonitorController(
    private val configService: LargeBetMonitorConfigService,
    private val watchRecordService: LargeBetWatchRecordService,
    private val activityMonitorService: LargeBetActivityMonitorService,
    private val telegramNotificationService: TelegramNotificationService
) {

    private val logger = LoggerFactory.getLogger(LargeBetMonitorController::class.java)

    @PostMapping("/config")
    fun getConfig(): ResponseEntity<ApiResponse<LargeBetMonitorConfigDto>> = runBlocking {
        ResponseEntity.ok(ApiResponse.success(configService.getConfig()))
    }

    @PostMapping("/config/update")
    fun updateConfig(@RequestBody request: LargeBetMonitorConfigUpdateRequest): ResponseEntity<ApiResponse<LargeBetMonitorConfigDto>> = runBlocking {
        val result = configService.updateConfig(request)
        activityMonitorService.reconcileConnection()
        ResponseEntity.ok(
            result.fold(
                onSuccess = { ApiResponse.success(it) },
                onFailure = { ApiResponse.error(ErrorCode.PARAM_ERROR, it.message ?: "Invalid large bet monitor config") }
            )
        )
    }

    @PostMapping("/records/list")
    fun listRecords(): ResponseEntity<ApiResponse<List<LargeBetWatchRecordDto>>> = runBlocking {
        ResponseEntity.ok(ApiResponse.success(watchRecordService.listRecords()))
    }

    @PostMapping("/status")
    fun status(): ResponseEntity<ApiResponse<LargeBetMonitorStatusDto>> = runBlocking {
        ResponseEntity.ok(ApiResponse.success(activityMonitorService.getStatus()))
    }

    @PostMapping("/test")
    fun test(): ResponseEntity<ApiResponse<Boolean>> = runBlocking {
        val config = configService.getConfigEntity()
        val success = try {
            telegramNotificationService.sendLargeBetMonitorMessage(
                message = "<b>大额投注监控测试</b>\n\n这是一条来自全平台赔率监控的大额投注监控测试消息。",
                configId = config.telegramConfigId
            )
        } catch (e: Exception) {
            logger.error("Large bet monitor test push failed: {}", e.message, e)
            false
        }
        ResponseEntity.ok(if (success) ApiResponse.success(true) else ApiResponse.error(ErrorCode.PARAM_ERROR, "Telegram test push failed"))
    }
}
