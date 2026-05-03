package com.wrbug.polymarketbot.service.system

import com.wrbug.polymarketbot.constants.PolymarketConstants
import com.wrbug.polymarketbot.dto.ApiHealthCheckDto
import com.wrbug.polymarketbot.service.copytrading.monitor.PolymarketActivityWsService
import kotlinx.coroutines.test.runTest
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Answers
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.springframework.context.ApplicationContext
import kotlin.reflect.full.callSuspend
import kotlin.reflect.full.declaredFunctions
import kotlin.reflect.jvm.isAccessible

class ApiHealthCheckServiceTest {

    private val rpcNodeService = mock(RpcNodeService::class.java)
    private val applicationContext = mock(ApplicationContext::class.java)
    private val activityWsService = mock(PolymarketActivityWsService::class.java)

    private val service = ApiHealthCheckService(rpcNodeService).also {
        it.setApplicationContext(applicationContext)
    }

    @Test
    fun `activity websocket health is skipped when no leader is monitored`() = runTest {
        `when`(applicationContext.getBean(PolymarketActivityWsService::class.java)).thenReturn(activityWsService)
        `when`(activityWsService.getMonitoredCount()).thenReturn(0)

        val result = invokeActivityHealthCheck()

        assertEquals("Polymarket Activity WebSocket", result.name)
        assertEquals(PolymarketConstants.ACTIVITY_WS_URL, result.url)
        assertEquals("skipped", result.status)
        assertEquals("未配置 Leader 监听", result.message)
    }

    @Test
    fun `activity websocket health stays successful when monitored leaders are connected`() = runTest {
        `when`(applicationContext.getBean(PolymarketActivityWsService::class.java)).thenReturn(activityWsService)
        `when`(activityWsService.getMonitoredCount()).thenReturn(2)
        `when`(activityWsService.isConnected()).thenReturn(true)

        val result = invokeActivityHealthCheck()

        assertEquals("success", result.status)
        assertEquals("连接正常", result.message)
    }

    @Test
    fun `checkApi closes the response after a successful health check`() = runTest {
        val builder = mock(OkHttpClient.Builder::class.java, Answers.RETURNS_SELF)
        val client = mock(OkHttpClient::class.java)
        val call = mock(Call::class.java)
        val requestUrl = "https://example.com/health"
        val responseBody = TrackingResponseBody("{}")
        val response = Response.Builder()
            .request(Request.Builder().url(requestUrl).build())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(responseBody)
            .build()
        val serviceWithMockClient = ApiHealthCheckService(rpcNodeService) { builder }

        `when`(builder.build()).thenReturn(client)
        `when`(client.newCall(anyRequest())).thenReturn(call)
        `when`(call.execute()).thenReturn(response)

        val result = invokeCheckApi(serviceWithMockClient, "Test API", requestUrl)

        assertEquals("success", result.status)
        assertTrue(responseBody.closed)
    }

    private suspend fun invokeActivityHealthCheck(): ApiHealthCheckDto =
        service::class.declaredFunctions.first { it.name == "checkPolymarketActivityWebSocket" }.let { method ->
            method.isAccessible = true
            method.callSuspend(service) as ApiHealthCheckDto
        }

    private suspend fun invokeCheckApi(
        targetService: ApiHealthCheckService,
        name: String,
        url: String
    ): ApiHealthCheckDto =
        targetService::class.declaredFunctions.first { it.name == "checkApi" }.let { method ->
            method.isAccessible = true
            method.callSuspend(targetService, name, url) as ApiHealthCheckDto
        }

    private class TrackingResponseBody(private val content: String) : ResponseBody() {
        var closed: Boolean = false
            private set

        override fun contentType() = "application/json".toMediaType()

        override fun contentLength() = content.toByteArray().size.toLong()

        override fun source() = Buffer().writeUtf8(content)

        override fun close() {
            closed = true
            super.close()
        }
    }

    private fun anyRequest(): Request =
        any(Request::class.java) ?: Request.Builder().url("https://placeholder.test").build()
}
