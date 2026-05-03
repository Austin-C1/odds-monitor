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
        
        @Deprecated("使用 error(ErrorCode.PARAM_ERROR, msg) 替代", ReplaceWith("error(ErrorCode.PARAM_ERROR, msg)"))
        fun <T> paramError(msg: String): ApiResponse<T> {
            return error(ErrorCode.PARAM_ERROR, msg)
        }
        
        @Deprecated("使用 error(ErrorCode.AUTH_ERROR, msg) 替代", ReplaceWith("error(ErrorCode.AUTH_ERROR, msg)"))
        fun <T> authError(msg: String): ApiResponse<T> {
            return error(ErrorCode.AUTH_ERROR, msg)
        }
        
        @Deprecated("使用 error(ErrorCode.NOT_FOUND, msg) 替代", ReplaceWith("error(ErrorCode.NOT_FOUND, msg)"))
        fun <T> notFound(msg: String): ApiResponse<T> {
            return error(ErrorCode.NOT_FOUND, msg)
        }
        
        @Deprecated("使用 error(ErrorCode.BUSINESS_ERROR, msg) 替代", ReplaceWith("error(ErrorCode.BUSINESS_ERROR, msg)"))
        fun <T> businessError(msg: String): ApiResponse<T> {
            return error(ErrorCode.BUSINESS_ERROR, msg)
        }
        
        @Deprecated("使用 error(ErrorCode.SERVER_ERROR, msg) 替代", ReplaceWith("error(ErrorCode.SERVER_ERROR, msg)"))
        fun <T> serverError(msg: String): ApiResponse<T> {
            return error(ErrorCode.SERVER_ERROR, msg)
        }
    }
}

