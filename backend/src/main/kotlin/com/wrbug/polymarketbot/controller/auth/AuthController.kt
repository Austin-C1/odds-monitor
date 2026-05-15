package com.wrbug.polymarketbot.controller.auth

import com.wrbug.polymarketbot.dto.ApiResponse
import com.wrbug.polymarketbot.dto.CheckFirstUseResponse
import com.wrbug.polymarketbot.dto.LoginRequest
import com.wrbug.polymarketbot.dto.LoginResponse
import com.wrbug.polymarketbot.dto.ResetPasswordRequest
import com.wrbug.polymarketbot.enums.ErrorCode
import com.wrbug.polymarketbot.repository.UserRepository
import com.wrbug.polymarketbot.service.auth.AuthService
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
    private val userRepository: UserRepository
) {
    private val logger = LoggerFactory.getLogger(AuthController::class.java)

    @PostMapping("/login")
    fun login(
        @RequestBody request: LoginRequest,
        httpRequest: HttpServletRequest
    ): ResponseEntity<ApiResponse<LoginResponse>> {
        if (request.username.isBlank()) {
            return errorResponse(HttpStatus.BAD_REQUEST, ErrorCode.PARAM_EMPTY, "用户名不能为空")
        }
        if (request.password.isBlank()) {
            return errorResponse(HttpStatus.BAD_REQUEST, ErrorCode.PARAM_EMPTY, "密码不能为空")
        }

        return authService.login(request.username, request.password, getClientIpAddress(httpRequest)).fold(
            onSuccess = { ResponseEntity.ok(ApiResponse.success(it)) },
            onFailure = { ex ->
                when (ex) {
                    is IllegalStateException ->
                        errorResponse(HttpStatus.TOO_MANY_REQUESTS, ErrorCode.AUTH_ERROR, ex.message ?: "登录失败")

                    is IllegalArgumentException ->
                        if (ex.message == ErrorCode.AUTH_USERNAME_OR_PASSWORD_ERROR.message) {
                            errorResponse(HttpStatus.UNAUTHORIZED, ErrorCode.AUTH_USERNAME_OR_PASSWORD_ERROR)
                        } else {
                            errorResponse(HttpStatus.BAD_REQUEST, ErrorCode.PARAM_ERROR, ex.message)
                        }

                    else -> {
                        logger.error("login failed: {}", ex.message, ex)
                        errorResponse(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.AUTH_ERROR, "登录失败")
                    }
                }
            }
        )
    }

    @PostMapping("/local-login")
    fun localLogin(httpRequest: HttpServletRequest): ResponseEntity<ApiResponse<LoginResponse>> {
        val ipAddress = httpRequest.remoteAddr.orEmpty().split(",").first().trim()
        if (!isLoopbackAddress(ipAddress)) {
            return errorResponse(HttpStatus.FORBIDDEN, ErrorCode.AUTH_ERROR, "免密登录只允许本机访问")
        }

        return authService.localLogin().fold(
            onSuccess = { ResponseEntity.ok(ApiResponse.success(it)) },
            onFailure = { ex ->
                logger.error("local login failed: {}", ex.message, ex)
                errorResponse(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.AUTH_ERROR, "免密登录失败")
            }
        )
    }

    @PostMapping("/reset-password")
    fun resetPassword(
        @RequestBody request: ResetPasswordRequest,
        httpRequest: HttpServletRequest
    ): ResponseEntity<ApiResponse<Unit>> {
        if (request.resetKey.isBlank()) {
            return errorResponse(HttpStatus.BAD_REQUEST, ErrorCode.PARAM_EMPTY, "重置密钥不能为空")
        }
        if (request.username.isBlank()) {
            return errorResponse(HttpStatus.BAD_REQUEST, ErrorCode.PARAM_EMPTY, "用户名不能为空")
        }
        if (request.newPassword.isBlank()) {
            return errorResponse(HttpStatus.BAD_REQUEST, ErrorCode.PARAM_EMPTY, "新密码不能为空")
        }

        return authService.resetPassword(request.resetKey, request.username, request.newPassword, httpRequest).fold(
            onSuccess = { ResponseEntity.ok(ApiResponse.success(Unit)) },
            onFailure = { ex ->
                logger.error("reset password failed: {}", ex.message, ex)
                when (ex) {
                    is IllegalArgumentException ->
                        if (ex.message == ErrorCode.AUTH_PASSWORD_WEAK.message) {
                            errorResponse(HttpStatus.BAD_REQUEST, ErrorCode.AUTH_PASSWORD_WEAK)
                        } else {
                            errorResponse(HttpStatus.UNAUTHORIZED, ErrorCode.AUTH_ERROR, "重置失败")
                        }

                    is IllegalStateException ->
                        errorResponse(HttpStatus.TOO_MANY_REQUESTS, ErrorCode.AUTH_ERROR, "重置失败")

                    else ->
                        errorResponse(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.AUTH_ERROR, "重置失败")
                }
            }
        )
    }

    @PostMapping("/check-first-use")
    fun checkFirstUse(): ResponseEntity<ApiResponse<CheckFirstUseResponse>> {
        return try {
            ResponseEntity.ok(ApiResponse.success(CheckFirstUseResponse(authService.isFirstUse())))
        } catch (ex: Exception) {
            logger.error("check first use failed: {}", ex.message, ex)
            errorResponse(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.SERVER_ERROR, "检查首次使用状态失败")
        }
    }

    @GetMapping("/verify")
    fun verify(httpRequest: HttpServletRequest): ResponseEntity<ApiResponse<Unit>> {
        return try {
            val username = httpRequest.getAttribute("username") as? String
                ?: return errorResponse(HttpStatus.UNAUTHORIZED, ErrorCode.AUTH_ERROR, "未认证")
            val user = userRepository.findByUsername(username)
            if (user == null || !user.isDefault) {
                return errorResponse(HttpStatus.FORBIDDEN, ErrorCode.AUTH_ERROR, "需要管理员权限")
            }
            ResponseEntity.ok(ApiResponse.success(Unit))
        } catch (ex: Exception) {
            logger.error("permission verify failed: {}", ex.message, ex)
            errorResponse(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.SERVER_ERROR, "权限验证失败")
        }
    }

    private fun getClientIpAddress(request: HttpServletRequest): String {
        val headerIp = listOf("X-Forwarded-For", "X-Real-IP", "Proxy-Client-IP")
            .firstNotNullOfOrNull { header ->
                request.getHeader(header)?.takeIf { it.isNotBlank() && !it.equals("unknown", ignoreCase = true) }
            }
        return (headerIp ?: request.remoteAddr).split(",").first().trim()
    }

    private fun isLoopbackAddress(ip: String): Boolean {
        return ip == "127.0.0.1" ||
            ip == "localhost" ||
            ip == "::1" ||
            ip == "0:0:0:0:0:0:0:1" ||
            ip.startsWith("127.")
    }

    private fun <T> errorResponse(
        status: HttpStatus,
        errorCode: ErrorCode,
        customMsg: String? = null
    ): ResponseEntity<ApiResponse<T>> {
        return ResponseEntity.status(status).body(ApiResponse.error(errorCode, customMsg, messageSource))
    }
}
