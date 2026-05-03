package com.wrbug.polymarketbot.controller.auth

import com.wrbug.polymarketbot.dto.ApiResponse
import com.wrbug.polymarketbot.dto.CheckFirstUseResponse
import com.wrbug.polymarketbot.dto.LoginRequest
import com.wrbug.polymarketbot.dto.LoginResponse
import com.wrbug.polymarketbot.dto.ResetPasswordRequest
import com.wrbug.polymarketbot.dto.WebSocketTicketResponse
import com.wrbug.polymarketbot.enums.ErrorCode
import com.wrbug.polymarketbot.repository.UserRepository
import com.wrbug.polymarketbot.service.auth.AuthService
import com.wrbug.polymarketbot.service.auth.WebSocketTicketService
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.context.MessageSource
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/auth")
class AuthController(
    private val authService: AuthService,
    private val messageSource: MessageSource,
    private val webSocketTicketService: WebSocketTicketService,
    private val userRepository: UserRepository
) {

    private val logger = LoggerFactory.getLogger(AuthController::class.java)

    @PostMapping("/login")
    fun login(
        @RequestBody request: LoginRequest,
        httpRequest: HttpServletRequest
    ): ResponseEntity<ApiResponse<LoginResponse>> {
        return try {
            if (request.username.isBlank()) {
                return errorResponse(HttpStatus.BAD_REQUEST, ErrorCode.PARAM_EMPTY, "用户名不能为空")
            }
            if (request.password.isBlank()) {
                return errorResponse(HttpStatus.BAD_REQUEST, ErrorCode.PARAM_EMPTY, "密码不能为空")
            }

            val ipAddress = getClientIpAddress(httpRequest)
            val result = authService.login(request.username, request.password, ipAddress)
            result.fold(
                onSuccess = { loginResponse ->
                    ResponseEntity.ok(ApiResponse.success(loginResponse))
                },
                onFailure = { e ->
                    when (e) {
                        is IllegalStateException ->
                            errorResponse(HttpStatus.TOO_MANY_REQUESTS, ErrorCode.AUTH_ERROR, e.message ?: "登录失败")

                        is IllegalArgumentException -> {
                            if (e.message == ErrorCode.AUTH_USERNAME_OR_PASSWORD_ERROR.message) {
                                errorResponse(HttpStatus.UNAUTHORIZED, ErrorCode.AUTH_USERNAME_OR_PASSWORD_ERROR)
                            } else {
                                errorResponse(HttpStatus.BAD_REQUEST, ErrorCode.PARAM_ERROR, e.message)
                            }
                        }

                        else -> errorResponse(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.AUTH_ERROR, "登录失败")
                    }
                }
            )
        } catch (e: Exception) {
            logger.error("登录异常: ${e.message}", e)
            errorResponse(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.AUTH_ERROR, "登录失败")
        }
    }

    @PostMapping("/local-login")
    fun localLogin(httpRequest: HttpServletRequest): ResponseEntity<ApiResponse<LoginResponse>> {
        return try {
            val ipAddress = getClientIpAddress(httpRequest)
            if (!isLoopbackAddress(ipAddress)) {
                return errorResponse(HttpStatus.FORBIDDEN, ErrorCode.AUTH_ERROR, "仅允许本机免密登录")
            }

            authService.localLogin().fold(
                onSuccess = { loginResponse ->
                    ResponseEntity.ok(ApiResponse.success(loginResponse))
                },
                onFailure = { e ->
                    logger.error("本机免密登录失败: ${e.message}", e)
                    errorResponse(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.AUTH_ERROR, "免密登录失败")
                }
            )
        } catch (e: Exception) {
            logger.error("本机免密登录异常: ${e.message}", e)
            errorResponse(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.AUTH_ERROR, "免密登录失败")
        }
    }

    private fun getClientIpAddress(request: HttpServletRequest): String {
        var ip = request.getHeader("X-Forwarded-For")
        if (ip.isNullOrBlank() || "unknown".equals(ip, ignoreCase = true)) {
            ip = request.getHeader("X-Real-IP")
        }
        if (ip.isNullOrBlank() || "unknown".equals(ip, ignoreCase = true)) {
            ip = request.getHeader("Proxy-Client-IP")
        }
        if (ip.isNullOrBlank() || "unknown".equals(ip, ignoreCase = true)) {
            ip = request.remoteAddr
        }
        if (ip.contains(",")) {
            ip = ip.split(",")[0].trim()
        }
        return ip
    }

    private fun isLoopbackAddress(ip: String): Boolean {
        return ip == "127.0.0.1" ||
            ip == "localhost" ||
            ip == "::1" ||
            ip == "0:0:0:0:0:0:0:1" ||
            ip.startsWith("127.")
    }

    @PostMapping("/reset-password")
    fun resetPassword(
        @RequestBody request: ResetPasswordRequest,
        httpRequest: HttpServletRequest
    ): ResponseEntity<ApiResponse<Unit>> {
        return try {
            if (request.resetKey.isBlank()) {
                return errorResponse(HttpStatus.BAD_REQUEST, ErrorCode.PARAM_EMPTY, "重置密钥不能为空")
            }
            if (request.username.isBlank()) {
                return errorResponse(HttpStatus.BAD_REQUEST, ErrorCode.PARAM_EMPTY, "用户名不能为空")
            }
            if (request.newPassword.isBlank()) {
                return errorResponse(HttpStatus.BAD_REQUEST, ErrorCode.PARAM_EMPTY, "新密码不能为空")
            }

            val result = authService.resetPassword(
                resetKey = request.resetKey,
                username = request.username,
                newPassword = request.newPassword,
                request = httpRequest
            )

            result.fold(
                onSuccess = {
                    ResponseEntity.ok(ApiResponse.success(Unit))
                },
                onFailure = { e ->
                    logger.error("重置密码失败: ${e.message}", e)
                    when (e) {
                        is IllegalArgumentException -> {
                            if (e.message == ErrorCode.AUTH_PASSWORD_WEAK.message) {
                                errorResponse(HttpStatus.BAD_REQUEST, ErrorCode.AUTH_PASSWORD_WEAK)
                            } else {
                                errorResponse(HttpStatus.UNAUTHORIZED, ErrorCode.AUTH_ERROR, "重置失败")
                            }
                        }

                        is IllegalStateException ->
                            errorResponse(HttpStatus.TOO_MANY_REQUESTS, ErrorCode.AUTH_ERROR, "重置失败")

                        else ->
                            errorResponse(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.AUTH_ERROR, "重置失败")
                    }
                }
            )
        } catch (e: Exception) {
            logger.error("重置密码异常: ${e.message}", e)
            errorResponse(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.AUTH_ERROR, "重置密码失败: ${e.message}")
        }
    }

    @PostMapping("/check-first-use")
    fun checkFirstUse(): ResponseEntity<ApiResponse<CheckFirstUseResponse>> {
        return try {
            val isFirstUse = authService.isFirstUse()
            ResponseEntity.ok(ApiResponse.success(CheckFirstUseResponse(isFirstUse = isFirstUse)))
        } catch (e: Exception) {
            logger.error("检查首次使用异常: ${e.message}", e)
            errorResponse(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.SERVER_ERROR, "检查首次使用失败: ${e.message}")
        }
    }

    @PostMapping("/ws-ticket")
    fun getWebSocketTicket(httpRequest: HttpServletRequest): ResponseEntity<ApiResponse<WebSocketTicketResponse>> {
        return try {
            val username = httpRequest.getAttribute("username") as? String
            if (username == null) {
                return errorResponse(HttpStatus.UNAUTHORIZED, ErrorCode.AUTH_ERROR, "未认证")
            }

            val ticket = webSocketTicketService.generateTicket(username)
            ResponseEntity.ok(ApiResponse.success(WebSocketTicketResponse(ticket = ticket)))
        } catch (e: Exception) {
            logger.error("获取 WebSocket 票据异常: ${e.message}", e)
            errorResponse(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.SERVER_ERROR, "获取票据失败")
        }
    }

    @GetMapping("/verify")
    fun verify(httpRequest: HttpServletRequest): ResponseEntity<ApiResponse<Unit>> {
        return try {
            val username = httpRequest.getAttribute("username") as? String
            if (username == null) {
                return errorResponse(HttpStatus.UNAUTHORIZED, ErrorCode.AUTH_ERROR, "未认证")
            }

            val user = userRepository.findByUsername(username)
            if (user == null || !user.isDefault) {
                return errorResponse(HttpStatus.FORBIDDEN, ErrorCode.AUTH_ERROR, "需要管理员权限")
            }

            ResponseEntity.ok(ApiResponse.success(Unit))
        } catch (e: Exception) {
            logger.error("验证权限异常: ${e.message}", e)
            errorResponse(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.SERVER_ERROR, "验证失败")
        }
    }

    private fun <T> errorResponse(
        status: HttpStatus,
        errorCode: ErrorCode,
        customMsg: String? = null
    ): ResponseEntity<ApiResponse<T>> {
        return ResponseEntity.status(status).body(ApiResponse.error(errorCode, customMsg, messageSource))
    }
}
