package com.wrbug.polymarketbot.websocket

import com.wrbug.polymarketbot.util.createClient
import com.wrbug.polymarketbot.util.getProxyConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.slf4j.LoggerFactory

class PolymarketWebSocketClient(
    private val url: String,
    private val sessionId: String,
    private val onMessage: (String) -> Unit,
    private val onOpen: (() -> Unit)? = null,
    private val onReconnect: (() -> Unit)? = null
) {

    private val logger = LoggerFactory.getLogger(PolymarketWebSocketClient::class.java)
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val stateLock = Any()

    @Volatile
    private var webSocket: WebSocket? = null

    @Volatile
    private var connected = false

    @Volatile
    private var reconnecting = false

    @Volatile
    private var shouldReconnect = true

    private var pingJob: Job? = null
    private var reconnectJob: Job? = null
    private var reconnectDelay = 3000L

    private val okHttpClient: OkHttpClient by lazy {
        val builder = createClient()
        getProxyConfig()?.let { builder.proxy(it) }
        builder.build()
    }

    fun connect() {
        synchronized(stateLock) {
            if (connected && webSocket != null) {
                return
            }
            shouldReconnect = true
            val request = Request.Builder().url(url).build()
            webSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                    val wasReconnecting = reconnecting
                    connected = true
                    reconnecting = false
                    reconnectDelay = 3000L
                    stopReconnect()
                    startPing()
                    if (wasReconnecting) {
                        onReconnect?.invoke()
                    } else {
                        onOpen?.invoke()
                    }
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    onMessage(text)
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    onMessage(bytes.utf8())
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    handleDisconnect(code != 1000)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    handleDisconnect(code != 1000)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) {
                    logger.error("Polymarket WebSocket failed: sessionId=$sessionId, error=${t.message}", t)
                    response?.let {
                        logger.error("WebSocket failure response: code=${it.code}, message=${it.message}")
                    }
                    handleDisconnect(shouldReconnect)
                }
            })
        }
    }

    private fun startPing() {
        stopPing()
        pingJob = scope.launch {
            while (isActive && connected) {
                delay(10000)
                if (!connected) {
                    break
                }
                try {
                    sendMessage("ping")
                } catch (e: Exception) {
                    logger.warn("Failed to send ping: sessionId=$sessionId, error=${e.message}")
                    break
                }
            }
        }
    }

    private fun stopPing() {
        pingJob?.cancel()
        pingJob = null
    }

    private fun scheduleReconnect() {
        if (!shouldReconnect) {
            return
        }
        val existingJob = reconnectJob
        if (existingJob != null && existingJob.isActive) {
            return
        }

        reconnectJob = scope.launch {
            delay(reconnectDelay)
            if (!shouldReconnect || connected) {
                return@launch
            }
            reconnecting = true
            synchronized(stateLock) {
                webSocket = null
            }
            runCatching { connect() }
                .onFailure { e ->
                    logger.error("Reconnect failed: sessionId=$sessionId, error=${e.message}", e)
                    reconnectDelay = (reconnectDelay * 2).coerceAtMost(60000L)
                    scheduleReconnect()
                }
        }
    }

    private fun stopReconnect() {
        reconnectJob?.cancel()
        reconnectJob = null
    }

    private fun handleDisconnect(allowReconnect: Boolean) {
        connected = false
        stopPing()
        synchronized(stateLock) {
            webSocket = null
        }
        if (allowReconnect && shouldReconnect) {
            reconnecting = true
            reconnectDelay = (reconnectDelay * 2).coerceAtMost(60000L)
            scheduleReconnect()
        }
    }

    fun closeConnection() {
        shouldReconnect = false
        reconnecting = false
        stopReconnect()
        stopPing()
        synchronized(stateLock) {
            webSocket?.close(1000, "normal_close")
            webSocket = null
        }
        okHttpClient.dispatcher.cancelAll()
        okHttpClient.dispatcher.executorService.shutdown()
        okHttpClient.connectionPool.evictAll()
        okHttpClient.cache?.close()
        connected = false
    }

    fun sendMessage(message: String) {
        val ws = webSocket
        if (ws == null || !connected) {
            throw IllegalStateException("WebSocket is not connected")
        }
        if (!ws.send(message)) {
            throw IllegalStateException("WebSocket send failed")
        }
    }

    fun isConnected(): Boolean = connected && webSocket != null
}
