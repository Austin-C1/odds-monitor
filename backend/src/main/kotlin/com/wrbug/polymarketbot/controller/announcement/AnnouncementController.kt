package com.wrbug.polymarketbot.controller.announcement

import com.wrbug.polymarketbot.dto.AnnouncementDetailRequest
import com.wrbug.polymarketbot.dto.AnnouncementDto
import com.wrbug.polymarketbot.dto.AnnouncementListRequest
import com.wrbug.polymarketbot.dto.AnnouncementListResponse
import com.wrbug.polymarketbot.dto.ApiResponse
import com.wrbug.polymarketbot.enums.ErrorCode
import com.wrbug.polymarketbot.service.announcement.AnnouncementService
import org.slf4j.LoggerFactory
import org.springframework.context.MessageSource
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/announcements")
class AnnouncementController(
    private val announcementService: AnnouncementService,
    private val messageSource: MessageSource
) {

    private val logger = LoggerFactory.getLogger(AnnouncementController::class.java)

    @PostMapping("/list")
    suspend fun getAnnouncementList(
        @RequestBody(required = false) request: AnnouncementListRequest?
    ): ResponseEntity<ApiResponse<AnnouncementListResponse>> {
        val normalizedRequest = request ?: AnnouncementListRequest()
        return try {
            announcementService.getAnnouncementList(normalizedRequest.forceRefresh).fold(
                onSuccess = { response ->
                    ResponseEntity.ok(ApiResponse.success(response))
                },
                onFailure = { error ->
                    logger.error("获取公告列表失败: ${error.message}", error)
                    ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_ERROR, error.message, messageSource))
                }
            )
        } catch (error: Exception) {
            logger.error("获取公告列表异常: ${error.message}", error)
            ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_ERROR, error.message, messageSource))
        }
    }

    @PostMapping("/detail")
    suspend fun getAnnouncementDetail(
        @RequestBody(required = false) request: AnnouncementDetailRequest?
    ): ResponseEntity<ApiResponse<AnnouncementDto>> {
        val normalizedRequest = request ?: AnnouncementDetailRequest()
        return try {
            announcementService.getAnnouncementDetail(normalizedRequest.id, normalizedRequest.forceRefresh).fold(
                onSuccess = { announcement ->
                    ResponseEntity.ok(ApiResponse.success(announcement))
                },
                onFailure = { error ->
                    logger.error("获取公告详情失败: ${error.message}", error)
                    when (error) {
                        is IllegalArgumentException -> ResponseEntity.ok(
                            ApiResponse.error(ErrorCode.PARAM_ERROR, error.message, messageSource)
                        )

                        else -> ResponseEntity.ok(
                            ApiResponse.error(ErrorCode.SERVER_ERROR, error.message, messageSource)
                        )
                    }
                }
            )
        } catch (error: Exception) {
            logger.error("获取公告详情异常: ${error.message}", error)
            ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_ERROR, error.message, messageSource))
        }
    }
}
