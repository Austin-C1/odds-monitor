package com.wrbug.polymarketbot.config

import com.wrbug.polymarketbot.dto.ApiResponse
import com.wrbug.polymarketbot.enums.ErrorCode
import org.springframework.core.MethodParameter
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.converter.HttpMessageConverter
import org.springframework.http.server.ServerHttpRequest
import org.springframework.http.server.ServerHttpResponse
import org.springframework.http.server.ServletServerHttpResponse
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice

@RestControllerAdvice
class ApiErrorHttpStatusAdvice : ResponseBodyAdvice<Any> {

    override fun supports(
        returnType: MethodParameter,
        converterType: Class<out HttpMessageConverter<*>>
    ): Boolean = true

    override fun beforeBodyWrite(
        body: Any?,
        returnType: MethodParameter,
        selectedContentType: MediaType,
        selectedConverterType: Class<out HttpMessageConverter<*>>,
        request: ServerHttpRequest,
        response: ServerHttpResponse
    ): Any? {
        val apiResponse = body as? ApiResponse<*> ?: return body
        if (apiResponse.code == 0) {
            return body
        }

        val targetStatus = resolveStatus(apiResponse.code) ?: return body
        val currentStatus = (response as? ServletServerHttpResponse)?.servletResponse?.status

        if (currentStatus == null || currentStatus == 0 || currentStatus == HttpStatus.OK.value()) {
            response.setStatusCode(targetStatus)
        }
        return body
    }

    internal fun resolveStatus(code: Int): HttpStatus? {
        return when {
            code in 400..599 -> HttpStatus.resolve(code)
            code == ErrorCode.AUTH_PERMISSION_DENIED.code -> HttpStatus.FORBIDDEN
            code == ErrorCode.AUTH_RESET_PASSWORD_RATE_LIMIT.code -> HttpStatus.TOO_MANY_REQUESTS
            code in 1001..1999 -> HttpStatus.BAD_REQUEST
            code in 2001..2999 -> HttpStatus.UNAUTHORIZED
            code in 3001..3999 -> HttpStatus.NOT_FOUND
            code in 5001..5999 -> HttpStatus.INTERNAL_SERVER_ERROR
            code in 4001..4999 -> resolveBusinessStatus(code)
            else -> null
        }
    }

    private fun resolveBusinessStatus(code: Int): HttpStatus {
        return when (code) {
            ErrorCode.LEADER_ALREADY_EXISTS.code,
            ErrorCode.LEADER_HAS_COPY_TRADINGS.code,
            ErrorCode.TEMPLATE_NAME_ALREADY_EXISTS.code,
            ErrorCode.TEMPLATE_HAS_COPY_TRADINGS.code,
            ErrorCode.COPY_TRADING_ALREADY_EXISTS.code,
            ErrorCode.COPY_TRADING_DISABLED.code,
            ErrorCode.COPY_TRADING_ENABLED.code,
            ErrorCode.ACCOUNT_ALREADY_EXISTS.code,
            ErrorCode.ACCOUNT_IS_DEFAULT.code,
            ErrorCode.ACCOUNT_HAS_ACTIVE_ORDERS.code,
            ErrorCode.ACCOUNT_IS_LAST_ONE.code,
            ErrorCode.BACKTEST_TASK_RUNNING.code,
            ErrorCode.BACKTEST_TASK_NOT_COMPLETED.code -> HttpStatus.CONFLICT
            else -> HttpStatus.BAD_REQUEST
        }
    }
}
