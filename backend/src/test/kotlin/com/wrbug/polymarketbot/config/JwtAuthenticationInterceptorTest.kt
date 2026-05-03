package com.wrbug.polymarketbot.config

import com.wrbug.polymarketbot.repository.UserRepository
import com.wrbug.polymarketbot.util.JwtUtils
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse

class JwtAuthenticationInterceptorTest {

    private val jwtUtils = mock(JwtUtils::class.java)
    private val userRepository = mock(UserRepository::class.java)
    private val interceptor = JwtAuthenticationInterceptor(jwtUtils, userRepository)

    @Test
    fun `missing bearer token returns unauthorized`() {
        val request = MockHttpServletRequest("GET", "/api/system/health")
        val response = MockHttpServletResponse()

        val allowed = interceptor.preHandle(request, response, Any())

        assertFalse(allowed)
        assertEquals(401, response.status)
        verify(jwtUtils, never()).validateToken(anyString())
    }

    @Test
    fun `invalid bearer token returns unauthorized`() {
        val request = MockHttpServletRequest("GET", "/api/system/health")
        request.addHeader("Authorization", "Bearer invalid-token")
        val response = MockHttpServletResponse()
        `when`(jwtUtils.validateToken("invalid-token")).thenReturn(false)

        val allowed = interceptor.preHandle(request, response, Any())

        assertFalse(allowed)
        assertEquals(401, response.status)
        verify(jwtUtils).validateToken("invalid-token")
    }
}
