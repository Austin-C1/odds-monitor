package com.wrbug.polymarketbot.controller.auth

import com.wrbug.polymarketbot.dto.LoginRequest
import com.wrbug.polymarketbot.dto.ResetPasswordRequest
import com.wrbug.polymarketbot.enums.ErrorCode
import com.wrbug.polymarketbot.repository.UserRepository
import com.wrbug.polymarketbot.service.auth.AuthService
import com.wrbug.polymarketbot.service.auth.WebSocketTicketService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.context.MessageSource
import org.springframework.http.HttpStatus
import org.springframework.mock.web.MockHttpServletRequest

class AuthControllerHttpStatusTest {

    private val authService = mock(AuthService::class.java)
    private val messageSource = mock(MessageSource::class.java)
    private val webSocketTicketService = mock(WebSocketTicketService::class.java)
    private val userRepository = mock(UserRepository::class.java)

    private val controller = AuthController(
        authService = authService,
        messageSource = messageSource,
        webSocketTicketService = webSocketTicketService,
        userRepository = userRepository
    )

    @Test
    fun `login returns 400 when username is blank`() {
        val response = controller.login(
            LoginRequest(username = "", password = "123456"),
            MockHttpServletRequest()
        )

        assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
    }

    @Test
    fun `login returns 401 when credentials are invalid`() {
        `when`(authService.login("demo", "wrong", "127.0.0.1")).thenReturn(
            Result.failure(IllegalArgumentException(ErrorCode.AUTH_USERNAME_OR_PASSWORD_ERROR.message))
        )

        val request = MockHttpServletRequest().apply {
            remoteAddr = "127.0.0.1"
        }
        val response = controller.login(LoginRequest(username = "demo", password = "wrong"), request)

        assertEquals(HttpStatus.UNAUTHORIZED, response.statusCode)
    }

    @Test
    fun `local login returns token for loopback request`() {
        `when`(authService.localLogin()).thenReturn(Result.success(com.wrbug.polymarketbot.dto.LoginResponse("local-token")))

        val request = MockHttpServletRequest().apply {
            remoteAddr = "127.0.0.1"
        }
        val response = controller.localLogin(request)

        assertEquals(HttpStatus.OK, response.statusCode)
        assertEquals("local-token", response.body?.data?.token)
    }

    @Test
    fun `local login rejects non loopback request`() {
        val request = MockHttpServletRequest().apply {
            remoteAddr = "10.0.0.20"
        }
        val response = controller.localLogin(request)

        assertEquals(HttpStatus.FORBIDDEN, response.statusCode)
    }

    @Test
    fun `reset password returns 400 when reset key is blank`() {
        val response = controller.resetPassword(
            ResetPasswordRequest(resetKey = "", username = "demo", newPassword = "123456"),
            MockHttpServletRequest()
        )

        assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
    }

    @Test
    fun `ws ticket returns 401 when request is unauthenticated`() {
        val response = controller.getWebSocketTicket(MockHttpServletRequest())

        assertEquals(HttpStatus.UNAUTHORIZED, response.statusCode)
    }
}
