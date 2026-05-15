package com.wrbug.polymarketbot.dto

import com.wrbug.polymarketbot.enums.ErrorCode
import org.springframework.context.MessageSource
import org.springframework.context.i18n.LocaleContextHolder

data class ApiResponse<T>(
    val code: Int,
    val data: T?,
    val msg: String
) {
    companion object {
        fun <T> success(data: T?): ApiResponse<T> {
            return ApiResponse(code = 0, data = data, msg = "")
        }
        
        fun <T> error(
            errorCode: ErrorCode,
            customMsg: String? = null,
            messageSource: MessageSource? = null
        ): ApiResponse<T> {
            val msg: String = if (customMsg != null) {
                customMsg
            } else if (messageSource != null) {
                try {
                    messageSource.getMessage(
                        errorCode.messageKey,
                        null,
                        errorCode.message,
                        LocaleContextHolder.getLocale()
                    ) ?: errorCode.message
                } catch (e: Exception) {
                    errorCode.message
                }
            } else {
                errorCode.message
            }
            
            return ApiResponse(
                code = errorCode.code,
                data = null,
                msg = msg
            )
        }
        
        fun <T> error(code: Int, msg: String): ApiResponse<T> {
            return ApiResponse(code = code, data = null, msg = msg)
        }
        
    }
}

