package com.wrbug.polymarketbot.config

import jakarta.servlet.FilterChain
import jakarta.servlet.ServletRequest
import jakarta.servlet.ServletResponse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse

class CorsPreflightFilterTest {

    @Test
    fun `api options request is handled before controller routing`() {
        val filter = CorsPreflightFilter("http://127.0.0.1:18881,http://localhost:18881")
        val request = MockHttpServletRequest("OPTIONS", "/api/auth/local-login").apply {
            addHeader("Origin", "http://127.0.0.1:18881")
            addHeader("Access-Control-Request-Method", "POST")
            addHeader("Access-Control-Request-Headers", "content-type")
        }
        val response = MockHttpServletResponse()
        var chainReached = false
        val chain = FilterChain { _: ServletRequest, _: ServletResponse ->
            chainReached = true
        }

        filter.doFilter(request, response, chain)

        assertEquals(204, response.status)
        assertFalse(chainReached)
        assertEquals("http://127.0.0.1:18881", response.getHeader("Access-Control-Allow-Origin"))
        assertEquals("GET,POST,PUT,DELETE,OPTIONS", response.getHeader("Access-Control-Allow-Methods"))
    }

    @Test
    fun `non options api request continues to controller routing`() {
        val filter = CorsPreflightFilter("http://127.0.0.1:18881")
        val request = MockHttpServletRequest("POST", "/api/auth/local-login")
        val response = MockHttpServletResponse()
        var chainReached = false
        val chain = FilterChain { _: ServletRequest, _: ServletResponse ->
            chainReached = true
        }

        filter.doFilter(request, response, chain)

        assertTrue(chainReached)
    }
}
