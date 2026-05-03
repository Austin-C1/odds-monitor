package com.wrbug.polymarketbot.config

import com.wrbug.polymarketbot.dto.ApiResponse
import com.wrbug.polymarketbot.repository.UserRepository
import com.wrbug.polymarketbot.util.JwtUtils
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.servlet.HandlerInterceptor

@Component
class JwtAuthenticationInterceptor(
    private val jwtUtils: JwtUtils,
    private val userRepository: UserRepository
) : HandlerInterceptor {
    
    private val logger = LoggerFactory.getLogger(JwtAuthenticationInterceptor::class.java)
    private val objectMapper = ObjectMapper()
    private val excludePaths = setOf(
        "/api/auth/login",
        "/api/auth/local-login",
        "/api/auth/reset-password",
        "/api/auth/check-first-use"
    )
    
    override fun preHandle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any
    ): Boolean {
        val path = request.requestURI
        if (!path.startsWith("/api/")) {
            return true
        }
        if (excludePaths.contains(path)) {
            return true
        }
        if (request.method == "OPTIONS") {
            return true
        }
        val authHeader = request.getHeader("Authorization")
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            sendAuthError(response, "缺少认证令牌")
            return false
        }
        
        val token = authHeader.substring(7)
        if (!jwtUtils.validateToken(token)) {
            logger.warn("Token验证失败: path=$path")
            sendAuthError(response, "认证令牌无效或已过期")
            return false
        }
        val username = jwtUtils.getUsernameFromToken(token)
        if (username != null) {
            val user = userRepository.findByUsername(username)
            if (user != null) {
                val tokenVersion = jwtUtils.getTokenVersionFromToken(token)
                if (tokenVersion == null || tokenVersion != user.tokenVersion) {
                    logger.warn("Token版本不匹配，token已失效: username=$username, tokenVersion=$tokenVersion, userTokenVersion=${user.tokenVersion}, path=$path")
                    sendAuthError(response, "认证令牌已失效，请重新登录")
                    return false
                }
            }
        }
        if (jwtUtils.isTokenExpiring(token)) {
            if (username != null) {
                val user = userRepository.findByUsername(username)
                if (user != null) {
                    val newToken = jwtUtils.generateToken(username, user.tokenVersion)
                    response.setHeader("X-New-Token", newToken)
                    logger.debug("Token自动刷新: username=$username, path=$path")
                }
            }
        }
        if (username != null) {
            request.setAttribute("username", username)
        }
        
        return true
    }
    
    private fun sendAuthError(response: HttpServletResponse, message: String) {
        response.status = HttpServletResponse.SC_UNAUTHORIZED
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.characterEncoding = "UTF-8"
        
        val apiResponse: ApiResponse<Unit> = ApiResponse.authError(message)
        val json = objectMapper.writeValueAsString(apiResponse)
        response.writer.write(json)
        response.writer.flush()
    }
}

