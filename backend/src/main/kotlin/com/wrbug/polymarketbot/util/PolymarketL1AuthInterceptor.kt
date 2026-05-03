package com.wrbug.polymarketbot.util

import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import org.slf4j.LoggerFactory
import java.io.IOException

class PolymarketL1AuthInterceptor(
    private val privateKey: String,
    private val walletAddress: String,
    private val chainId: Long = 137L,
    private val nonce: Long = 0L,
    private val useServerTime: Boolean = false,
    private val serverTime: Long? = null
) : Interceptor {
    
    private val logger = LoggerFactory.getLogger(PolymarketL1AuthInterceptor::class.java)
    
    @Throws(IOException::class)
    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        val timestamp = if (useServerTime && serverTime != null) {
            serverTime
        } else {
            System.currentTimeMillis() / 1000
        }
        val signature = try {
            Eip712Signer.buildClobEip712Signature(
                privateKey = privateKey,
                chainId = chainId,
                timestamp = timestamp,
                nonce = nonce
            )
        } catch (e: Exception) {
            logger.error("生成 EIP-712 签名失败", e)
            throw IOException("生成签名失败: ${e.message}", e)
        }
        val newRequest = originalRequest.newBuilder()
            .header("POLY_ADDRESS", walletAddress)
            .header("POLY_SIGNATURE", signature)
            .header("POLY_TIMESTAMP", timestamp.toString())
            .header("POLY_NONCE", nonce.toString())
            .build()
        
        return chain.proceed(newRequest)
    }
}

