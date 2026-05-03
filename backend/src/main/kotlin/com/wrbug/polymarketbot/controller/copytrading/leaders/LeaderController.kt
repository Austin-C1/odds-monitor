package com.wrbug.polymarketbot.controller.copytrading.leaders

import com.wrbug.polymarketbot.dto.*
import com.wrbug.polymarketbot.enums.ErrorCode
import com.wrbug.polymarketbot.service.copytrading.leaders.LeaderService
import org.slf4j.LoggerFactory
import org.springframework.context.MessageSource
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/copy-trading/leaders")
class LeaderController(
    private val leaderService: LeaderService,
    private val messageSource: MessageSource
) {
    
    private val logger = LoggerFactory.getLogger(LeaderController::class.java)
    
    @PostMapping("/add")
    fun addLeader(@RequestBody request: LeaderAddRequest): ResponseEntity<ApiResponse<LeaderDto>> {
        return try {
            if (request.leaderAddress.isBlank()) {
                return ResponseEntity.ok(ApiResponse.error(ErrorCode.PARAM_LEADER_ADDRESS_EMPTY, messageSource = messageSource))
            }
            
            val result = leaderService.addLeader(request)
            result.fold(
                onSuccess = { leader ->
                    ResponseEntity.ok(ApiResponse.success(leader))
                },
                onFailure = { e ->
                    logger.error("添加 Leader 失败: ${e.message}", e)
                    when (e) {
                        is IllegalArgumentException -> ResponseEntity.ok(ApiResponse.error(ErrorCode.PARAM_ERROR, e.message, messageSource))
                        is IllegalStateException -> ResponseEntity.ok(ApiResponse.error(ErrorCode.BUSINESS_ERROR, e.message, messageSource))
                        else -> ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_LEADER_ADD_FAILED, e.message, messageSource))
                    }
                }
            )
        } catch (e: Exception) {
            logger.error("添加 Leader 异常: ${e.message}", e)
            ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_LEADER_ADD_FAILED, e.message, messageSource))
        }
    }
    
    @PostMapping("/update")
    fun updateLeader(@RequestBody request: LeaderUpdateRequest): ResponseEntity<ApiResponse<LeaderDto>> {
        return try {
            if (request.leaderId <= 0) {
                return ResponseEntity.ok(ApiResponse.error(ErrorCode.PARAM_LEADER_ID_INVALID, messageSource = messageSource))
            }
            
            val result = leaderService.updateLeader(request)
            result.fold(
                onSuccess = { leader ->
                    ResponseEntity.ok(ApiResponse.success(leader))
                },
                onFailure = { e ->
                    logger.error("更新 Leader 失败: ${e.message}", e)
                    when (e) {
                        is IllegalArgumentException -> ResponseEntity.ok(ApiResponse.error(ErrorCode.PARAM_ERROR, e.message, messageSource))
                        else -> ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_LEADER_UPDATE_FAILED, e.message, messageSource))
                    }
                }
            )
        } catch (e: Exception) {
            logger.error("更新 Leader 异常: ${e.message}", e)
            ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_LEADER_UPDATE_FAILED, e.message, messageSource))
        }
    }
    
    @PostMapping("/delete")
    fun deleteLeader(@RequestBody request: LeaderDeleteRequest): ResponseEntity<ApiResponse<Unit>> {
        return try {
            if (request.leaderId <= 0) {
                return ResponseEntity.ok(ApiResponse.error(ErrorCode.PARAM_LEADER_ID_INVALID, messageSource = messageSource))
            }
            
            val result = leaderService.deleteLeader(request.leaderId)
            result.fold(
                onSuccess = {
                    ResponseEntity.ok(ApiResponse.success(Unit))
                },
                onFailure = { e ->
                    logger.error("删除 Leader 失败: ${e.message}", e)
                    when (e) {
                        is IllegalArgumentException -> ResponseEntity.ok(ApiResponse.error(ErrorCode.PARAM_ERROR, e.message, messageSource))
                        is IllegalStateException -> ResponseEntity.ok(ApiResponse.error(ErrorCode.BUSINESS_ERROR, e.message, messageSource))
                        else -> ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_LEADER_DELETE_FAILED, e.message, messageSource))
                    }
                }
            )
        } catch (e: Exception) {
            logger.error("删除 Leader 异常: ${e.message}", e)
            ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_LEADER_DELETE_FAILED, e.message, messageSource))
        }
    }
    
    @PostMapping("/list")
    fun getLeaderList(@RequestBody request: LeaderListRequest): ResponseEntity<ApiResponse<LeaderListResponse>> {
        return try {
            val result = leaderService.getLeaderList(request)
            result.fold(
                onSuccess = { response ->
                    ResponseEntity.ok(ApiResponse.success(response))
                },
                onFailure = { e ->
                    logger.error("查询 Leader 列表失败: ${e.message}", e)
                    ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_LEADER_LIST_FETCH_FAILED, e.message, messageSource))
                }
            )
        } catch (e: Exception) {
            logger.error("查询 Leader 列表异常: ${e.message}", e)
            ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_LEADER_LIST_FETCH_FAILED, e.message, messageSource))
        }
    }
    
    @PostMapping("/detail")
    fun getLeaderDetail(@RequestBody request: LeaderDetailRequest): ResponseEntity<ApiResponse<LeaderDto>> {
        return try {
            if (request.leaderId <= 0) {
                return ResponseEntity.ok(ApiResponse.error(ErrorCode.PARAM_LEADER_ID_INVALID, messageSource = messageSource))
            }

            val result = leaderService.getLeaderDetail(request.leaderId)
            result.fold(
                onSuccess = { leader ->
                    ResponseEntity.ok(ApiResponse.success(leader))
                },
                onFailure = { e ->
                    logger.error("查询 Leader 详情失败: ${e.message}", e)
                    when (e) {
                        is IllegalArgumentException -> ResponseEntity.ok(ApiResponse.error(ErrorCode.PARAM_ERROR, e.message, messageSource))
                        else -> ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_LEADER_DETAIL_FETCH_FAILED, e.message, messageSource))
                    }
                }
            )
        } catch (e: Exception) {
            logger.error("查询 Leader 详情异常: ${e.message}", e)
            ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_LEADER_DETAIL_FETCH_FAILED, e.message, messageSource))
        }
    }

    @PostMapping("/balance")
    fun getLeaderBalance(@RequestBody request: LeaderBalanceRequest): ResponseEntity<ApiResponse<LeaderBalanceResponse>> {
        return try {
            if (request.leaderId <= 0) {
                return ResponseEntity.ok(ApiResponse.error(ErrorCode.PARAM_LEADER_ID_INVALID, messageSource = messageSource))
            }

            val result = leaderService.getLeaderBalance(request.leaderId)
            result.fold(
                onSuccess = { balance ->
                    ResponseEntity.ok(ApiResponse.success(balance))
                },
                onFailure = { e ->
                    logger.error("查询 Leader 余额失败: ${e.message}", e)
                    when (e) {
                        is IllegalArgumentException -> ResponseEntity.ok(ApiResponse.error(ErrorCode.PARAM_ERROR, e.message, messageSource))
                        is IllegalStateException -> ResponseEntity.ok(ApiResponse.error(ErrorCode.BUSINESS_ERROR, e.message, messageSource))
                        else -> ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_ERROR, e.message, messageSource))
                    }
                }
            )
        } catch (e: Exception) {
            logger.error("查询 Leader 余额异常: ${e.message}", e)
            ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_ERROR, e.message, messageSource))
        }
    }
}

data class LeaderDetailRequest(
    val leaderId: Long
)





