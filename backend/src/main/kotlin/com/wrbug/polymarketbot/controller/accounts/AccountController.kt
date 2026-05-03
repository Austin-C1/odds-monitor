package com.wrbug.polymarketbot.controller.accounts

import com.wrbug.polymarketbot.dto.*
import com.wrbug.polymarketbot.enums.ErrorCode
import com.wrbug.polymarketbot.service.accounts.AccountService
import com.wrbug.polymarketbot.util.toSafeBigDecimal
import org.slf4j.LoggerFactory
import org.springframework.context.MessageSource
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.math.BigDecimal

@RestController
@RequestMapping("/api/accounts")
class AccountController(
    private val accountService: AccountService,
    private val messageSource: MessageSource
) {

    private val logger = LoggerFactory.getLogger(AccountController::class.java)

    @PostMapping("/check-proxy-options")
    suspend fun checkProxyOptions(@RequestBody request: CheckProxyOptionsRequest): ResponseEntity<ApiResponse<CheckProxyOptionsResponse>> {
        return try {
            if (request.walletAddress.isBlank()) {
                return ResponseEntity.ok(ApiResponse.error(ErrorCode.PARAM_WALLET_ADDRESS_EMPTY, messageSource = messageSource))
            }
            if (request.privateKey.isNullOrBlank() && request.mnemonic.isNullOrBlank()) {
                return ResponseEntity.ok(ApiResponse.error(ErrorCode.PARAM_ERROR, "必须提供私钥或助记词", messageSource))
            }

            val result = accountService.checkProxyOptions(request)
            result.fold(
                onSuccess = { response ->
                    ResponseEntity.ok(ApiResponse.success(response))
                },
                onFailure = { e ->
                    logger.error("检查代理地址选项失败: ${e.message}", e)
                    when (e) {
                        is IllegalArgumentException -> ResponseEntity.ok(
                            ApiResponse.error(
                                ErrorCode.PARAM_ERROR,
                                e.message,
                                messageSource
                            )
                        )
                        else -> ResponseEntity.ok(
                            ApiResponse.error(
                                ErrorCode.SERVER_ERROR,
                                e.message,
                                messageSource
                            )
                        )
                    }
                }
            )
        } catch (e: Exception) {
            logger.error("检查代理地址选项异常: ${e.message}", e)
            ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_ERROR, e.message, messageSource))
        }
    }

    @PostMapping("/import")
    suspend fun importAccount(@RequestBody request: AccountImportRequest): ResponseEntity<ApiResponse<AccountDto>> {
        return try {
            if (request.privateKey.isBlank()) {
                return ResponseEntity.ok(ApiResponse.error(ErrorCode.PARAM_PRIVATE_KEY_EMPTY, messageSource = messageSource))
            }
            if (request.walletAddress.isBlank()) {
                return ResponseEntity.ok(ApiResponse.error(ErrorCode.PARAM_WALLET_ADDRESS_EMPTY, messageSource = messageSource))
            }

            val result = accountService.importAccount(request)
            result.fold(
                onSuccess = { account ->
                    ResponseEntity.ok(ApiResponse.success(account))
                },
                onFailure = { e ->
                    logger.error("导入账户失败: ${e.message}", e)
                    when (e) {
                        is IllegalArgumentException -> if (e.message == "ACCOUNT_ALREADY_EXISTS") {
                            ResponseEntity.ok(ApiResponse.error(ErrorCode.ACCOUNT_ALREADY_EXISTS, messageSource = messageSource))
                        } else {
                            ResponseEntity.ok(
                                ApiResponse.error(
                                    ErrorCode.PARAM_ERROR,
                                    e.message,
                                    messageSource
                                )
                            )
                        }
                        else -> ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_ACCOUNT_IMPORT_FAILED, e.message, messageSource))
                    }
                }
            )
        } catch (e: Exception) {
            logger.error("导入账户异常: ${e.message}", e)
            ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_ACCOUNT_IMPORT_FAILED, e.message, messageSource))
        }
    }

    @PostMapping("/update")
    suspend fun updateAccount(@RequestBody request: AccountUpdateRequest): ResponseEntity<ApiResponse<AccountDto>> {
        return try {
            val result = accountService.updateAccount(request)
            result.fold(
                onSuccess = { account ->
                    ResponseEntity.ok(ApiResponse.success(account))
                },
                onFailure = { e ->
                    logger.error("更新账户失败: ${e.message}", e)
                    when (e) {
                        is IllegalArgumentException -> ResponseEntity.ok(
                            ApiResponse.error(
                                ErrorCode.PARAM_ERROR,
                                e.message,
                                messageSource
                            )
                        )

                        else -> ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_ACCOUNT_UPDATE_FAILED, e.message, messageSource))
                    }
                }
            )
        } catch (e: Exception) {
            logger.error("更新账户异常: ${e.message}", e)
            ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_ACCOUNT_UPDATE_FAILED, e.message, messageSource))
        }
    }

    @PostMapping("/delete")
    fun deleteAccount(@RequestBody request: AccountDeleteRequest): ResponseEntity<ApiResponse<Unit>> {
        return try {
            val result = accountService.deleteAccount(request.accountId)
            result.fold(
                onSuccess = {
                    ResponseEntity.ok(ApiResponse.success(Unit))
                },
                onFailure = { e ->
                    logger.error("删除账户失败: ${e.message}", e)
                    when (e) {
                        is IllegalArgumentException -> ResponseEntity.ok(
                            ApiResponse.error(
                                ErrorCode.PARAM_ERROR,
                                e.message,
                                messageSource
                            )
                        )

                        is IllegalStateException -> ResponseEntity.ok(
                            ApiResponse.error(
                                ErrorCode.BUSINESS_ERROR,
                                e.message,
                                messageSource
                            )
                        )

                        else -> ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_ACCOUNT_DELETE_FAILED, e.message, messageSource))
                    }
                }
            )
        } catch (e: Exception) {
            logger.error("删除账户异常: ${e.message}", e)
            ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_ACCOUNT_DELETE_FAILED, e.message, messageSource))
        }
    }

    @PostMapping("/list")
    fun getAccountList(): ResponseEntity<ApiResponse<AccountListResponse>> {
        return try {
            val result = accountService.getAccountList()
            result.fold(
                onSuccess = { response ->
                    ResponseEntity.ok(ApiResponse.success(response))
                },
                onFailure = { e ->
                    logger.error("查询账户列表失败: ${e.message}", e)
                    ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_ACCOUNT_LIST_FETCH_FAILED, e.message, messageSource))
                }
            )
        } catch (e: Exception) {
            logger.error("查询账户列表异常: ${e.message}", e)
            ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_ACCOUNT_LIST_FETCH_FAILED, e.message, messageSource))
        }
    }

    @PostMapping("/check-setup-status")
    suspend fun checkSetupStatus(@RequestBody request: AccountDetailRequest): ResponseEntity<ApiResponse<AccountSetupStatusDto>> {
        return try {
            if (request.accountId == null || request.accountId <= 0) {
                return ResponseEntity.ok(ApiResponse.error(ErrorCode.PARAM_ACCOUNT_ID_INVALID, messageSource = messageSource))
            }
            val result = accountService.checkAccountSetupStatus(request.accountId)
            result.fold(
                onSuccess = { status ->
                    ResponseEntity.ok(ApiResponse.success(status))
                },
                onFailure = { e ->
                    logger.error("检查账户设置状态失败: ${e.message}", e)
                    when (e) {
                        is IllegalArgumentException -> ResponseEntity.ok(
                            ApiResponse.error(
                                ErrorCode.PARAM_ERROR,
                                e.message,
                                messageSource
                            )
                        )
                        else -> ResponseEntity.ok(
                            ApiResponse.error(
                                ErrorCode.SERVER_ERROR,
                                e.message,
                                messageSource
                            )
                        )
                    }
                }
            )
        } catch (e: Exception) {
            logger.error("检查账户设置状态异常: ${e.message}", e)
            ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_ERROR, e.message, messageSource))
        }
    }

    @PostMapping("/execute-setup-step")
    suspend fun executeSetupStep(@RequestBody request: ExecuteSetupStepRequest): ResponseEntity<ApiResponse<ExecuteSetupStepResponse>> {
        return try {
            if (request.accountId == null || request.accountId <= 0) {
                return ResponseEntity.ok(ApiResponse.error(ErrorCode.PARAM_ACCOUNT_ID_INVALID, messageSource = messageSource))
            }
            val step = request.step ?: 0
            if (step !in 1..3) {
                return ResponseEntity.ok(ApiResponse.error(ErrorCode.PARAM_ERROR, "步骤必须为 1、2 或 3", messageSource))
            }
            val result = accountService.executeSetupStep(request.accountId, step)
            result.fold(
                onSuccess = { response ->
                    ResponseEntity.ok(ApiResponse.success(response))
                },
                onFailure = { e ->
                    logger.error("执行设置步骤失败: ${e.message}", e)
                    when (e) {
                        is IllegalArgumentException -> ResponseEntity.ok(
                            ApiResponse.error(ErrorCode.PARAM_ERROR, e.message, messageSource)
                        )
                        else -> ResponseEntity.ok(
                            ApiResponse.error(ErrorCode.SERVER_ERROR, e.message, messageSource)
                        )
                    }
                }
            )
        } catch (e: Exception) {
            logger.error("执行设置步骤异常: ${e.message}", e)
            ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_ERROR, e.message, messageSource))
        }
    }

    @PostMapping("/detail")
    suspend fun getAccountDetail(@RequestBody request: AccountDetailRequest): ResponseEntity<ApiResponse<AccountDto>> {
        return try {
            val result = accountService.getAccountDetail(request.accountId)
            result.fold(
                onSuccess = { account ->
                    ResponseEntity.ok(ApiResponse.success(account))
                },
                onFailure = { e ->
                    logger.error("查询账户详情失败: ${e.message}", e)
                    when (e) {
                        is IllegalArgumentException -> ResponseEntity.ok(
                            ApiResponse.error(
                                ErrorCode.PARAM_ERROR,
                                e.message,
                                messageSource
                            )
                        )

                        else -> ResponseEntity.ok(
                            ApiResponse.error(
                                ErrorCode.SERVER_ACCOUNT_DETAIL_FETCH_FAILED,
                                e.message,
                                messageSource
                            )
                        )
                    }
                }
            )
        } catch (e: Exception) {
            logger.error("查询账户详情异常: ${e.message}", e)
            ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_ACCOUNT_DETAIL_FETCH_FAILED, e.message, messageSource))
        }
    }

    @PostMapping("/balance")
    suspend fun getAccountBalance(@RequestBody request: AccountBalanceRequest): ResponseEntity<ApiResponse<AccountBalanceResponse>> {
        return try {
            val result = accountService.getAccountBalance(request.accountId)
            result.fold(
                onSuccess = { balance ->
                    ResponseEntity.ok(ApiResponse.success(balance))
                },
                onFailure = { e ->
                    logger.error("查询账户余额失败: ${e.message}", e)
                    when (e) {
                        is IllegalArgumentException -> ResponseEntity.ok(
                            ApiResponse.error(
                                ErrorCode.PARAM_ERROR,
                                e.message,
                                messageSource
                            )
                        )

                        else -> ResponseEntity.ok(
                            ApiResponse.error(
                                ErrorCode.SERVER_ACCOUNT_BALANCE_FETCH_FAILED,
                                e.message,
                                messageSource
                            )
                        )
                    }
                }
            )
        } catch (e: Exception) {
            logger.error("查询账户余额异常: ${e.message}", e)
            ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_ACCOUNT_BALANCE_FETCH_FAILED, e.message, messageSource))
        }
    }

    @PostMapping("/positions/list")
    suspend fun getAllPositions(): ResponseEntity<ApiResponse<PositionListResponse>> {
        return try {
            val result = accountService.getAllPositions()
            result.fold(
                onSuccess = { positionListResponse ->
                    ResponseEntity.ok(ApiResponse.success(positionListResponse))
                },
                onFailure = { e ->
                    logger.error("查询仓位列表失败: ${e.message}", e)
                    ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_ACCOUNT_POSITIONS_FETCH_FAILED, e.message, messageSource))
                }
            )
        } catch (e: Exception) {
            logger.error("查询仓位列表异常: ${e.message}", e)
            ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_ACCOUNT_POSITIONS_FETCH_FAILED, e.message, messageSource))
        }
    }

    @PostMapping("/positions/sell")
    suspend fun sellPosition(@RequestBody request: PositionSellRequest): ResponseEntity<ApiResponse<PositionSellResponse>> {
        return try {
            if (request.accountId <= 0) {
                return ResponseEntity.ok(ApiResponse.error(ErrorCode.PARAM_ACCOUNT_ID_INVALID, messageSource = messageSource))
            }
            if (request.marketId.isBlank()) {
                return ResponseEntity.ok(ApiResponse.error(ErrorCode.PARAM_MARKET_ID_EMPTY, messageSource = messageSource))
            }
            if (request.side.isBlank()) {
                return ResponseEntity.ok(ApiResponse.error(ErrorCode.PARAM_SIDE_EMPTY, messageSource = messageSource))
            }
            if (request.orderType !in listOf("MARKET", "LIMIT")) {
                return ResponseEntity.ok(ApiResponse.error(ErrorCode.PARAM_ORDER_TYPE_MUST_BE_MARKET_OR_LIMIT, messageSource = messageSource))
            }
            if (request.percent.isNullOrBlank() && request.quantity.isNullOrBlank()) {
                return ResponseEntity.ok(ApiResponse.error(ErrorCode.PARAM_QUANTITY_EMPTY, messageSource = messageSource))
            }
            if (!request.percent.isNullOrBlank()) {
                try {
                    val percent = request.percent.toSafeBigDecimal()
                    if (percent <= BigDecimal.ZERO || percent > BigDecimal.valueOf(100)) {
                        return ResponseEntity.ok(ApiResponse.error(ErrorCode.PARAM_ERROR, "卖出百分比必须在 0-100 之间", messageSource))
                    }
                } catch (e: Exception) {
                    return ResponseEntity.ok(ApiResponse.error(ErrorCode.PARAM_ERROR, "卖出百分比格式不正确: ${e.message}", messageSource))
                }
            }
            if (request.orderType == "LIMIT" && (request.price == null || request.price.isBlank())) {
                return ResponseEntity.ok(ApiResponse.error(ErrorCode.PARAM_PRICE_EMPTY, messageSource = messageSource))
            }

            val result = accountService.sellPosition(request)
            result.fold(
                onSuccess = { response ->
                    ResponseEntity.ok(ApiResponse.success(response))
                },
                onFailure = { e ->
                    logger.error("创建卖出订单失败: ${e.message}", e)
                    when (e) {
                        is IllegalArgumentException -> ResponseEntity.ok(
                            ApiResponse.error(
                                ErrorCode.PARAM_ERROR,
                                e.message,
                                messageSource
                            )
                        )

                        is IllegalStateException -> ResponseEntity.ok(
                            ApiResponse.error(
                                ErrorCode.BUSINESS_ERROR,
                                e.message,
                                messageSource
                            )
                        )

                        else -> ResponseEntity.ok(
                            ApiResponse.error(
                                ErrorCode.SERVER_ACCOUNT_ORDER_CREATE_FAILED,
                                e.message,
                                messageSource
                            )
                        )
                    }
                }
            )
        } catch (e: Exception) {
            logger.error("创建卖出订单异常: ${e.message}", e)
            ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_ACCOUNT_ORDER_CREATE_FAILED, e.message, messageSource))
        }
    }

    @PostMapping("/positions/redeemable-summary")
    suspend fun getRedeemablePositionsSummary(@RequestBody request: AccountDetailRequest): ResponseEntity<ApiResponse<RedeemablePositionsSummary>> {
        return try {
            val result = accountService.getRedeemablePositionsSummary(request.accountId)
            result.fold(
                onSuccess = { summary ->
                    ResponseEntity.ok(ApiResponse.success(summary))
                },
                onFailure = { e ->
                    logger.error("获取可赎回仓位统计失败: ${e.message}", e)
                    when (e) {
                        is IllegalArgumentException -> ResponseEntity.ok(
                            ApiResponse.error(
                                ErrorCode.PARAM_ERROR,
                                e.message,
                                messageSource
                            )
                        )

                        else -> ResponseEntity.ok(
                            ApiResponse.error(
                                ErrorCode.SERVER_ERROR,
                                "获取可赎回仓位统计失败: ${e.message}",
                                messageSource
                            )
                        )
                    }
                }
            )
        } catch (e: Exception) {
            logger.error("获取可赎回仓位统计异常: ${e.message}", e)
            ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_ERROR, "获取可赎回仓位统计失败: ${e.message}", messageSource))
        }
    }

    @PostMapping("/positions/redeem")
    suspend fun redeemPositions(@RequestBody request: PositionRedeemRequest): ResponseEntity<ApiResponse<PositionRedeemResponse>> {
        return try {
            if (request.positions.isEmpty()) {
                return ResponseEntity.ok(ApiResponse.error(ErrorCode.PARAM_REDEEM_POSITIONS_EMPTY, messageSource = messageSource))
            }
            for (item in request.positions) {
                if (item.accountId <= 0) {
                    return ResponseEntity.ok(ApiResponse.error(ErrorCode.PARAM_ACCOUNT_ID_INVALID, messageSource = messageSource))
                }
                if (item.marketId.isBlank()) {
                    return ResponseEntity.ok(ApiResponse.error(ErrorCode.PARAM_MARKET_ID_EMPTY, messageSource = messageSource))
                }
                if (item.outcomeIndex < 0) {
                    return ResponseEntity.ok(ApiResponse.error(ErrorCode.PARAM_INDEX_SETS_INVALID, messageSource = messageSource))
                }
            }

            val result = accountService.redeemPositions(request)
            result.fold(
                onSuccess = { response ->
                    ResponseEntity.ok(ApiResponse.success(response))
                },
                onFailure = { e ->
                    logger.error("赎回仓位失败: ${e.message}", e)
                    when (e) {
                        is IllegalArgumentException -> ResponseEntity.ok(
                            ApiResponse.error(
                                ErrorCode.PARAM_ERROR,
                                e.message,
                                messageSource
                            )
                        )

                        is IllegalStateException -> {
                            if (e.message?.contains("Builder API Key 未配置") == true) {
                                ResponseEntity.ok(
                                    ApiResponse.error(
                                        ErrorCode.BUILDER_API_KEY_NOT_CONFIGURED,
                                        "${e.message} 请前往系统设置页面配置：/system-settings",
                                        messageSource
                                    )
                                )
                            } else {
                                ResponseEntity.ok(
                            ApiResponse.error(
                                ErrorCode.BUSINESS_ERROR,
                                e.message,
                                messageSource
                            )
                        )
                            }
                        }

                        else -> ResponseEntity.ok(
                            ApiResponse.error(
                                ErrorCode.SERVER_ACCOUNT_REDEEM_POSITIONS_FAILED,
                                e.message,
                                messageSource
                            )
                        )
                    }
                }
            )
        } catch (e: Exception) {
            logger.error("赎回仓位异常: ${e.message}", e)
            ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_ACCOUNT_REDEEM_POSITIONS_FAILED, e.message, messageSource))
        }
    }

}

