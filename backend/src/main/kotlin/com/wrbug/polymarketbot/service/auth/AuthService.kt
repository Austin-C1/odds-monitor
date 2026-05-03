package com.wrbug.polymarketbot.service.auth

import com.wrbug.polymarketbot.dto.CheckFirstUseResponse
import com.wrbug.polymarketbot.dto.LoginResponse
import com.wrbug.polymarketbot.entity.User
import com.wrbug.polymarketbot.enums.ErrorCode
import com.wrbug.polymarketbot.repository.UserRepository
import com.wrbug.polymarketbot.util.JwtUtils
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import com.wrbug.polymarketbot.service.common.RateLimitService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class AuthService(
    private val userRepository: UserRepository,
    private val jwtUtils: JwtUtils,
    private val rateLimitService: RateLimitService
) {
    
    private val logger = LoggerFactory.getLogger(AuthService::class.java)
    private val passwordEncoder = BCryptPasswordEncoder()
    private val packagedDefaultAdminLock = Any()
    
    @Value("\${admin.reset-password.key}")
    private lateinit var resetPasswordKey: String

    @Value("\${odds.monitor.package.default-admin.enabled:false}")
    private var packagedDefaultAdminEnabled: Boolean = false

    @Value("\${odds.monitor.package.default-admin.username:123456}")
    private lateinit var packagedDefaultAdminUsername: String

    @Value("\${odds.monitor.package.default-admin.password:123456}")
    private lateinit var packagedDefaultAdminPassword: String
    
    fun login(username: String, password: String, ipAddress: String): Result<LoginResponse> {
        return try {
            ensurePackagedDefaultAdminExists()
            rateLimitService.checkLoginRateLimit(ipAddress).fold(
                onSuccess = { },
                onFailure = { e ->
                    return Result.failure(IllegalStateException(e.message ?: "登录频率限制"))
                }
            )

            val user = userRepository.findByUsername(username)
            if (user == null) {
                val lockoutMsg = rateLimitService.recordLoginFailure(ipAddress)
                if (lockoutMsg != null) {
                    return Result.failure(IllegalStateException(lockoutMsg))
                }
                return Result.failure(IllegalArgumentException(ErrorCode.AUTH_USERNAME_OR_PASSWORD_ERROR.message))
            }
            if (!passwordEncoder.matches(password, user.password)) {
                val lockoutMsg = rateLimitService.recordLoginFailure(ipAddress)
                if (lockoutMsg != null) {
                    return Result.failure(IllegalStateException(lockoutMsg))
                }
                return Result.failure(IllegalArgumentException(ErrorCode.AUTH_USERNAME_OR_PASSWORD_ERROR.message))
            }
            rateLimitService.clearLoginFailures(ipAddress)
            val token = jwtUtils.generateToken(username, user.tokenVersion)

            logger.info("用户登录成功：username=$username")
            Result.success(LoginResponse(token = token))
        } catch (e: Exception) {
            logger.error("登录异常：username=$username", e)
            Result.failure(e)
        }
    }
    
    @Transactional
    fun resetPassword(
        resetKey: String,
        username: String,
        newPassword: String,
        request: HttpServletRequest
    ): Result<Unit> {
        return try {
            rateLimitService.checkResetPasswordRateLimit().fold(
                onSuccess = { },
                onFailure = { e ->
                    logger.warn("重置密码频率限制触发：username=$username")
                    return Result.failure(IllegalStateException("重置失败"))
                }
            )
            if (resetKey != resetPasswordKey) {
                logger.warn("重置密码失败：重置密钥错误，username=$username")
                return Result.failure(IllegalArgumentException("重置失败"))
            }
            if (!checkPasswordStrength(newPassword)) {
                logger.warn("重置密码失败：密码强度不符合要求，username=$username")
                return Result.failure(IllegalArgumentException(ErrorCode.AUTH_PASSWORD_WEAK.message))
            }
            val existingUser = userRepository.findByUsername(username)
            
            if (existingUser != null) {
                val encodedPassword = passwordEncoder.encode(newPassword)
                val updatedUser = existingUser.copy(
                    password = encodedPassword,
                    tokenVersion = existingUser.tokenVersion + 1,
                    updatedAt = System.currentTimeMillis()
                )
                userRepository.save(updatedUser)
                logger.info("密码重置成功：username=$username, tokenVersion=${updatedUser.tokenVersion}")
            } else {
                val isFirstUse = userRepository.count() == 0L
                if (isFirstUse) {
                    val encodedPassword = passwordEncoder.encode(newPassword)
                    val newUser = User(
                        username = username,
                        password = encodedPassword,
                        isDefault = true,
                        tokenVersion = 0,
                        createdAt = System.currentTimeMillis(),
                        updatedAt = System.currentTimeMillis()
                    )
                    userRepository.save(newUser)
                    logger.info("首次使用，创建默认账户成功：username=$username")
                } else {
                    logger.warn("重置密码失败：用户不存在，username=$username")
                    return Result.failure(IllegalArgumentException("重置失败"))
                }
            }
            
            Result.success(Unit)
        } catch (e: Exception) {
            logger.error("重置密码异常：username=$username", e)
            Result.failure(e)
        }
    }
    
    fun refreshToken(token: String): Result<String> {
        return try {
            if (!jwtUtils.validateToken(token)) {
                return Result.failure(IllegalArgumentException(ErrorCode.AUTH_TOKEN_INVALID.message))
            }
            
            if (!jwtUtils.isTokenExpiring(token)) {
                return Result.success(token)
            }
            val username = jwtUtils.getUsernameFromToken(token)
                ?: return Result.failure(IllegalArgumentException(ErrorCode.AUTH_TOKEN_INVALID.message))
            val user = userRepository.findByUsername(username)
                ?: return Result.failure(IllegalArgumentException(ErrorCode.AUTH_TOKEN_INVALID.message))
            
            val newToken = jwtUtils.generateToken(username, user.tokenVersion)
            logger.debug("Token刷新成功：username=$username")
            Result.success(newToken)
        } catch (e: Exception) {
            logger.error("刷新token异常", e)
            Result.failure(e)
        }
    }
    
    fun isFirstUse(): Boolean {
        ensurePackagedDefaultAdminExists()
        return userRepository.count() == 0L
    }

    private fun ensurePackagedDefaultAdminExists() {
        if (!packagedDefaultAdminEnabled || userRepository.count() > 0L) {
            return
        }

        synchronized(packagedDefaultAdminLock) {
            if (userRepository.count() > 0L) {
                return
            }

            val now = System.currentTimeMillis()
            val defaultAdmin = User(
                username = packagedDefaultAdminUsername,
                password = passwordEncoder.encode(packagedDefaultAdminPassword),
                isDefault = true,
                tokenVersion = 0,
                createdAt = now,
                updatedAt = now
            )
            userRepository.save(defaultAdmin)
            logger.info("打包版本已自动创建默认管理员：username=$packagedDefaultAdminUsername")
        }
    }

    fun localLogin(): Result<LoginResponse> {
        return try {
            ensurePackagedDefaultAdminExists()
            val user = userRepository.findByUsername(packagedDefaultAdminUsername)
                ?: userRepository.findByIsDefaultTrue()
                ?: return Result.failure(IllegalStateException("Default admin not found"))
            val token = jwtUtils.generateToken(user.username, user.tokenVersion)
            Result.success(LoginResponse(token = token))
        } catch (e: Exception) {
            logger.error("本机免密登录异常", e)
            Result.failure(e)
        }
    }
    
    private fun checkPasswordStrength(password: String): Boolean {
        return password.length >= 6
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
            ip = request.getHeader("WL-Proxy-Client-IP")
        }
        if (ip.isNullOrBlank() || "unknown".equals(ip, ignoreCase = true)) {
            ip = request.remoteAddr
        }
        if (ip.contains(",")) {
            ip = ip.split(",")[0].trim()
        }
        return ip
    }
}

