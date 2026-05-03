package com.wrbug.polymarketbot.util

import com.wrbug.polymarketbot.enums.ErrorCode
import org.springframework.context.MessageSource
import org.springframework.context.i18n.LocaleContextHolder
import org.springframework.stereotype.Component

@Component
class MessageUtils(
    private val messageSource: MessageSource
) {
    fun getMessage(errorCode: ErrorCode): String {
        return try {
            messageSource.getMessage(
                errorCode.messageKey,
                null,
                errorCode.message,
                LocaleContextHolder.getLocale()
            ) ?: errorCode.message
        } catch (e: Exception) {
            errorCode.message
        }
    }
    
    fun getMessage(key: String, defaultMessage: String = key, vararg args: Any?): String {
        return try {
            messageSource.getMessage(
                key,
                args,
                defaultMessage,
                LocaleContextHolder.getLocale()
            ) ?: defaultMessage
        } catch (e: Exception) {
            defaultMessage
        }
    }
}