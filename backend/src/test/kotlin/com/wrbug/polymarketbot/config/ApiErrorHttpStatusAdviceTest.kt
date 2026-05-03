package com.wrbug.polymarketbot.config

import com.wrbug.polymarketbot.dto.ApiResponse
import com.wrbug.polymarketbot.enums.ErrorCode
import org.junit.jupiter.api.Test
import org.springframework.http.ResponseEntity
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

class ApiErrorHttpStatusAdviceTest {

    private val mockMvc: MockMvc = MockMvcBuilders
        .standaloneSetup(SampleController())
        .setControllerAdvice(ApiErrorHttpStatusAdvice())
        .build()

    @Test
    fun `maps parameter errors to 400`() {
        mockMvc.perform(get("/api/test/param-error"))
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `maps permission errors to 403`() {
        mockMvc.perform(get("/api/test/forbidden"))
            .andExpect(status().isForbidden)
    }

    @Test
    fun `maps not found errors to 404`() {
        mockMvc.perform(get("/api/test/not-found"))
            .andExpect(status().isNotFound)
    }

    @Test
    fun `keeps successful responses at 200`() {
        mockMvc.perform(get("/api/test/success"))
            .andExpect(status().isOk)
    }

    @RestController
    private class SampleController {

        @GetMapping("/api/test/param-error")
        fun paramError(): ResponseEntity<ApiResponse<Unit>> {
            return ResponseEntity.ok(ApiResponse.error(ErrorCode.PARAM_ERROR, "bad request"))
        }

        @GetMapping("/api/test/forbidden")
        fun forbidden(): ResponseEntity<ApiResponse<Unit>> {
            return ResponseEntity.ok(ApiResponse.error(ErrorCode.AUTH_PERMISSION_DENIED, "forbidden"))
        }

        @GetMapping("/api/test/not-found")
        fun notFound(): ResponseEntity<ApiResponse<Unit>> {
            return ResponseEntity.ok(ApiResponse.error(ErrorCode.NOT_FOUND, "missing"))
        }

        @GetMapping("/api/test/success")
        fun success(): ResponseEntity<ApiResponse<String>> {
            return ResponseEntity.ok(ApiResponse.success("ok"))
        }
    }
}
