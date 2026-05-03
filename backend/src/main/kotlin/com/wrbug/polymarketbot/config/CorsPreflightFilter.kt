package com.wrbug.polymarketbot.config

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class CorsPreflightFilter(
    @Value("\${cors.allowed-origins:http://127.0.0.1:18881,http://localhost:18881}")
    allowedOriginsConfig: String
) : OncePerRequestFilter() {

    private val allowedOrigins = allowedOriginsConfig
        .split(",")
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .toSet()

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain
    ) {
        val origin = request.getHeader("Origin")
        if (origin != null && allowedOrigins.contains(origin)) {
            response.setHeader("Access-Control-Allow-Origin", origin)
            response.setHeader("Vary", "Origin")
            response.setHeader("Access-Control-Allow-Credentials", "true")
            response.setHeader("Access-Control-Allow-Headers", request.getHeader("Access-Control-Request-Headers") ?: "*")
            response.setHeader("Access-Control-Allow-Methods", "GET,POST,PUT,DELETE,OPTIONS")
            response.setHeader("Access-Control-Expose-Headers", "X-New-Token")
            response.setHeader("Access-Control-Max-Age", "3600")
        }

        if (request.method.equals("OPTIONS", ignoreCase = true) && request.requestURI.startsWith("/api/")) {
            response.status = HttpServletResponse.SC_NO_CONTENT
            return
        }

        filterChain.doFilter(request, response)
    }
}
