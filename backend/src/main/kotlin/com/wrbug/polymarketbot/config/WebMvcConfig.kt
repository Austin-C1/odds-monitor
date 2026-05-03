package com.wrbug.polymarketbot.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.config.annotation.CorsRegistry
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

@Configuration
class WebMvcConfig(
    private val jwtAuthenticationInterceptor: JwtAuthenticationInterceptor,
    private val localeInterceptor: LocaleInterceptor,
    @Value("\${cors.allowed-origins:http://127.0.0.1:18881,http://localhost:18881}")
    private val allowedOriginsConfig: String
) : WebMvcConfigurer {

    private fun getAllowedOrigins(): Array<String> {
        return allowedOriginsConfig
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toTypedArray()
    }

    override fun addCorsMappings(registry: CorsRegistry) {
        registry.addMapping("/api/**")
            .allowedOrigins(*getAllowedOrigins())
            .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
            .allowedHeaders("*")
            .exposedHeaders("X-New-Token")
            .maxAge(3600)
    }

    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(localeInterceptor)
            .addPathPatterns("/api/**")
        registry.addInterceptor(jwtAuthenticationInterceptor)
            .addPathPatterns("/api/**")
    }
}

