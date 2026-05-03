package com.wrbug.polymarketbot.config

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.context.i18n.LocaleContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.servlet.HandlerInterceptor
import java.util.*

@Component
class LocaleInterceptor : HandlerInterceptor {
    
    override fun preHandle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any
    ): Boolean {
        val language = request.getHeader("X-Language")
            ?: request.getHeader("Accept-Language")
            ?: "en"
        val locale = parseLocale(language)
        LocaleContextHolder.setLocale(locale)
        
        return true
    }
    
    private fun parseLocale(language: String): Locale {
        val lang = language.trim().lowercase()
        if (lang.startsWith("zh")) {
            if (lang.contains("tw") || lang.contains("hk") || lang.contains("mo")) {
                return Locale("zh", "TW")
            }
            return Locale("zh", "CN")
        }
        return Locale("en")
    }
}
