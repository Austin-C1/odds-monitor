package com.wrbug.polymarketbot.util

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.Buffer
import org.slf4j.LoggerFactory
import java.io.IOException
import java.time.Instant
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.util.Base64

/**
 * 
 * 
 *    - POLY_API_KEY: API Key
 *    - POLY_PASSPHRASE: Passphrase
 */
class PolymarketAuthInterceptor(
    private val apiKey: String,
    private val apiSecret: String,
    private val apiPassphrase: String,
    private val walletAddress: String
) : Interceptor {
    
    private val logger = LoggerFactory.getLogger(PolymarketAuthInterceptor::class.java)
    
    @Throws(IOException::class)
    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        val timestamp = Instant.now().epochSecond
        val method = originalRequest.method.uppercase()
        val requestPath = originalRequest.url.encodedPath
        val bodyString = originalRequest.body?.let { requestBody ->
            val buffer = Buffer()
            requestBody.writeTo(buffer)
            buffer.readUtf8()
        }
        // clob-client: timestamp + method + requestPath + (body !== undefined ? body : "")
        val signString = if (bodyString != null) {
            "$timestamp$method$requestPath$bodyString"
        } else {
            "$timestamp$method$requestPath"
        }
        val signature = generateSignature(signString, apiSecret)
        val newRequestBody = originalRequest.body?.let { requestBody ->
            val contentType = requestBody.contentType()
            RequestBody.create(
                contentType,
                bodyString?.toByteArray() ?: ByteArray(0)
            )
        }
        val newRequestBuilder = originalRequest.newBuilder()
            .header("POLY_ADDRESS", walletAddress)
            .header("POLY_SIGNATURE", signature)
            .header("POLY_TIMESTAMP", timestamp.toString())
            .header("POLY_API_KEY", apiKey)
            .header("POLY_PASSPHRASE", apiPassphrase)
            .header("User-Agent", "@polymarket/clob-client")
            .header("Accept", "*/*")
            .header("Connection", "keep-alive")
        if (newRequestBody != null) {
            newRequestBuilder.method(originalRequest.method, newRequestBody)
        }
        
        return chain.proceed(newRequestBuilder.build())
    }
    
    private fun generateSignature(message: String, secret: String): String {
        val decodedSecret = try {
            Base64.getDecoder().decode(secret)
        } catch (e: Exception) {
            try {
                Base64.getUrlDecoder().decode(secret)
            } catch (e2: Exception) {
                val standardBase64 = secret.replace("-", "+").replace("_", "/")
                try {
                    Base64.getDecoder().decode(standardBase64)
                } catch (e3: Exception) {
                    secret.toByteArray()
                }
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

