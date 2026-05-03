package com.wrbug.polymarketbot.util

import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import org.slf4j.LoggerFactory
import java.io.IOException
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 
 * 
 *    - POLY_BUILDER_API_KEY: API Key
 *    - POLY_BUILDER_PASSPHRASE: Passphrase
 */
class BuilderAuthInterceptor(
    private val apiKey: String,
    private val secret: String,
    private val passphrase: String
) : Interceptor {
    
    private val logger = LoggerFactory.getLogger(BuilderAuthInterceptor::class.java)
    
    @Throws(IOException::class)
    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        val requestBody = originalRequest.body
        val bodyString = if (requestBody != null) {
            val buffer = okio.Buffer()
            requestBody.writeTo(buffer)
            buffer.readUtf8()
        } else {
            ""
        }
        val timestamp = System.currentTimeMillis().toString()
        val method = originalRequest.method
        val path = originalRequest.url.encodedPath
        val signString = "$timestamp$method$path$bodyString"
        val signature = try {
            buildHmacSignature(signString, secret)
        } catch (e: Exception) {
            logger.error("生成 Builder HMAC 签名失败", e)
            throw IOException("生成签名失败: ${e.message}", e)
        }
        val newRequest = originalRequest.newBuilder()
            .header("POLY_BUILDER_SIGNATURE", signature)
            .header("POLY_BUILDER_TIMESTAMP", timestamp)
            .header("POLY_BUILDER_API_KEY", apiKey)
            .header("POLY_BUILDER_PASSPHRASE", passphrase)
            .build()
        
        return chain.proceed(newRequest)
    }
    
    private fun buildHmacSignature(message: String, secret: String): String {
        val decodedSecret = try {
            Base64.getDecoder().decode(secret)
        } catch (e: Exception) {
            try {
                Base64.getUrlDecoder().decode(secret)
            } catch (e2: Exception) {
                secret.toByteArray()
            }
        }
        val mac = Mac.getInstance("HmacSHA256")
        val secretKeySpec = SecretKeySpec(decodedSecret, "HmacSHA256")
        mac.init(secretKeySpec)
        val hash = mac.doFinal(message.toByteArray())
        val base64Signature = Base64.getEncoder().encodeToString(hash)
        return base64Signature.replace("+", "-").replace("/", "_")
    }
}

