package com.wrbug.polymarketbot.util

import com.google.gson.Gson
import com.wrbug.polymarketbot.api.BinanceApi
import com.wrbug.polymarketbot.api.BuilderRelayerApi
import com.wrbug.polymarketbot.api.EthereumRpcApi
import com.wrbug.polymarketbot.api.PolymarketClobApi
import com.wrbug.polymarketbot.api.PolymarketDataApi
import com.wrbug.polymarketbot.api.PolymarketGammaApi
import com.wrbug.polymarketbot.constants.PolymarketConstants
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Buffer
import java.util.concurrent.TimeUnit
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import jakarta.annotation.PreDestroy

@Component
class RetrofitFactory(
    private val gson: Gson
) {
    
    private val logger = LoggerFactory.getLogger(RetrofitFactory::class.java)

    companion object {
        internal const val FAST_TRADING_CONNECT_TIMEOUT_SECONDS = 3L
        internal const val FAST_TRADING_READ_TIMEOUT_SECONDS = 5L
        internal const val FAST_TRADING_WRITE_TIMEOUT_SECONDS = 3L
    }

    private data class AuthenticatedClobApiCacheKey(
        val walletAddress: String,
        val credentialFingerprint: String
    )
    private val sharedOkHttpClient: OkHttpClient by lazy {
        createClient().build()
    }
    private val sharedOkHttpClientWithRedirect: OkHttpClient by lazy {
        createClient()
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    private val fastTradingOkHttpClient: OkHttpClient by lazy {
        createFastTradingClientBuilder().build()
    }
    private val gammaApi: PolymarketGammaApi by lazy {
        val baseUrl = if (PolymarketConstants.GAMMA_BASE_URL.endsWith("/")) {
            PolymarketConstants.GAMMA_BASE_URL.dropLast(1)
        } else {
            PolymarketConstants.GAMMA_BASE_URL
        }
        
        Retrofit.Builder()
            .baseUrl("$baseUrl/")
            .client(sharedOkHttpClient)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
            .create(PolymarketGammaApi::class.java)
    }
    private val dataApi: PolymarketDataApi by lazy {
        val baseUrl = PolymarketConstants.DATA_API_BASE_URL
        
        Retrofit.Builder()
            .baseUrl("$baseUrl/")
            .client(sharedOkHttpClientWithRedirect)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
            .create(PolymarketDataApi::class.java)
    }
    private val clobApiWithoutAuth: PolymarketClobApi by lazy {
        buildClobApi(sharedOkHttpClient)
    }

    private val fastTradingClobApiWithoutAuth: PolymarketClobApi by lazy {
        buildClobApi(fastTradingOkHttpClient)
    }
    private val clobApiCache = ConcurrentHashMap<AuthenticatedClobApiCacheKey, PolymarketClobApi>()
    private val fastTradingClobApiCache = ConcurrentHashMap<AuthenticatedClobApiCacheKey, PolymarketClobApi>()
    private val rpcApiCache = ConcurrentHashMap<String, EthereumRpcApi>()
    private val builderRelayerApiCache = ConcurrentHashMap<String, BuilderRelayerApi>()

    internal fun createFastTradingClientBuilder(): OkHttpClient.Builder {
        return createClient()
            .connectTimeout(FAST_TRADING_CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(FAST_TRADING_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(FAST_TRADING_WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }

    private fun buildClobApi(client: OkHttpClient): PolymarketClobApi {
        val baseUrl = if (PolymarketConstants.CLOB_BASE_URL.endsWith("/")) {
            PolymarketConstants.CLOB_BASE_URL
        } else {
            "${PolymarketConstants.CLOB_BASE_URL}/"
        }
        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
            .create(PolymarketClobApi::class.java)
    }

    private fun buildAuthenticatedClobApi(
        apiKey: String,
        apiSecret: String,
        apiPassphrase: String,
        walletAddress: String,
        clientBuilder: OkHttpClient.Builder
    ): PolymarketClobApi {
        val authInterceptor = PolymarketAuthInterceptor(apiKey, apiSecret, apiPassphrase, walletAddress)
        val responseLoggingInterceptor = ResponseLoggingInterceptor()
        return buildClobApi(
            clientBuilder
                .addInterceptor(authInterceptor)
                .addInterceptor(responseLoggingInterceptor)
            .build()
        )
    }

    private fun buildAuthenticatedClobApiCacheKey(
        apiKey: String,
        apiSecret: String,
        apiPassphrase: String,
        walletAddress: String
    ): AuthenticatedClobApiCacheKey {
        return AuthenticatedClobApiCacheKey(
            walletAddress = walletAddress,
            credentialFingerprint = credentialFingerprint(apiKey, apiSecret, apiPassphrase)
        )
    }

    private fun credentialFingerprint(
        apiKey: String,
        apiSecret: String,
        apiPassphrase: String
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(
            listOf(apiKey, apiSecret, apiPassphrase).joinToString("|").toByteArray(Charsets.UTF_8)
        )
        return bytes.joinToString("") { "%02x".format(it) }
    }
    
    /**
     * @param apiKey API Key
     * @param apiSecret API Secret
     * @param apiPassphrase API Passphrase
     */
    fun createClobApi(
        apiKey: String,
        apiSecret: String,
        apiPassphrase: String,
        walletAddress: String
    ): PolymarketClobApi {
        val cacheKey = buildAuthenticatedClobApiCacheKey(
            apiKey = apiKey,
            apiSecret = apiSecret,
            apiPassphrase = apiPassphrase,
            walletAddress = walletAddress
        )
        return clobApiCache.computeIfAbsent(cacheKey) {
            buildAuthenticatedClobApi(
                apiKey = apiKey,
                apiSecret = apiSecret,
                apiPassphrase = apiPassphrase,
                walletAddress = walletAddress,
                clientBuilder = createClient()
            )
        }
    }

    fun createClobApiWithoutAuth(): PolymarketClobApi {
        return clobApiWithoutAuth
    }

    fun createFastTradingClobApi(
        apiKey: String,
        apiSecret: String,
        apiPassphrase: String,
        walletAddress: String
    ): PolymarketClobApi {
        val cacheKey = buildAuthenticatedClobApiCacheKey(
            apiKey = apiKey,
            apiSecret = apiSecret,
            apiPassphrase = apiPassphrase,
            walletAddress = walletAddress
        )
        return fastTradingClobApiCache.computeIfAbsent(cacheKey) {
            buildAuthenticatedClobApi(
                apiKey = apiKey,
                apiSecret = apiSecret,
                apiPassphrase = apiPassphrase,
                walletAddress = walletAddress,
                clientBuilder = createFastTradingClientBuilder()
            )
        }
    }

    fun createFastTradingClobApiWithoutAuth(): PolymarketClobApi {
        return fastTradingClobApiWithoutAuth
    }
    
    fun createEthereumRpcApi(rpcUrl: String): EthereumRpcApi {
        val actualRpcUrl = if (rpcUrl.endsWith("/")) {
            rpcUrl
        } else {
            "$rpcUrl/"
        }
        return rpcApiCache.computeIfAbsent(actualRpcUrl) {
            validateRpcAvailability(actualRpcUrl)
            val fixedBaseUrl = "https://rpc-placeholder.invalid/"
            val urlReplaceInterceptor = RpcUrlReplaceInterceptor(fixedBaseUrl, actualRpcUrl)
            
            val okHttpClient = createClient()
                .addInterceptor(urlReplaceInterceptor)
                .build()
            
            Retrofit.Builder()
                .baseUrl(fixedBaseUrl)
                .client(okHttpClient)
                .addConverterFactory(GsonConverterFactory.create(gson))
                .build()
                .create(EthereumRpcApi::class.java)
        }
    }
    
    private fun validateRpcAvailability(rpcUrl: String) {
        try {
            val httpUrl = rpcUrl.toHttpUrlOrNull()
                ?: throw IllegalArgumentException("无效的 RPC URL: $rpcUrl")
            val jsonRpcRequest = """
                {
                    "jsonrpc": "2.0",
                    "method": "eth_blockNumber",
                    "params": [],
                    "id": 1
                }
            """.trimIndent()
            
            val mediaType = "application/json; charset=utf-8".toMediaType()
            val requestBody = jsonRpcRequest.toRequestBody(mediaType)
            val request = Request.Builder()
                .url(httpUrl)
                .post(requestBody)
                .header("Content-Type", "application/json")
                .build()
            val testClient = createClient()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .writeTimeout(5, TimeUnit.SECONDS)
                .build()
            testClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IllegalArgumentException("RPC endpoint returned HTTP ${response.code} ${response.message}")
                }
                
                val responseBody = response.body?.string()
                if (responseBody.isNullOrBlank()) {
                    throw IllegalArgumentException("RPC endpoint returned an empty response body")
                }
                if (responseBody.contains("\"error\"")) {
                    throw IllegalArgumentException("RPC endpoint returned an error payload: $responseBody")
                }
                if (!responseBody.contains("\"result\"")) {
                    throw IllegalArgumentException("RPC endpoint response does not contain a result field: $responseBody")
                }
            }
            
            logger.debug("RPC endpoint validation passed: $rpcUrl")
        } catch (e: IllegalArgumentException) {
            logger.error("RPC endpoint validation failed: $rpcUrl - ${e.message}")
            throw e
        } catch (e: Exception) {
            logger.error("RPC endpoint validation threw an unexpected error: $rpcUrl - ${e.message}", e)
            throw IllegalArgumentException("RPC endpoint validation failed: ${e.message}", e)
        }
    }
    
    fun createGammaApi(): PolymarketGammaApi {
        return gammaApi
    }
    
    fun createDataApi(): PolymarketDataApi {
        return dataApi
    }

    private val binanceApi: BinanceApi by lazy {
        Retrofit.Builder()
            .baseUrl("https://api.binance.com/")
            .client(sharedOkHttpClient)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
            .create(BinanceApi::class.java)
    }

    fun createBinanceApi(): BinanceApi = binanceApi

    /**
     * @param relayerUrl Builder Relayer URL
     * @param apiKey Builder API Key
     * @param secret Builder Secret
     * @param passphrase Builder Passphrase
     */
    fun createBuilderRelayerApi(
        relayerUrl: String,
        apiKey: String,
        secret: String,
        passphrase: String
    ): BuilderRelayerApi {
        val baseUrl = if (relayerUrl.endsWith("/")) {
            relayerUrl.dropLast(1)
        } else {
            relayerUrl
        }
        return builderRelayerApiCache.computeIfAbsent(baseUrl) {
            val builderAuthInterceptor = BuilderAuthInterceptor(apiKey, secret, passphrase)
            val okHttpClient = createClient()
                .addInterceptor(builderAuthInterceptor)
                .build()
            
            Retrofit.Builder()
                .baseUrl("$baseUrl/")
                .client(okHttpClient)
                .addConverterFactory(GsonConverterFactory.create(gson))
                .build()
                .create(BuilderRelayerApi::class.java)
        }
    }
    
    @PreDestroy
    fun destroy() {
        logger.info("Clearing RetrofitFactory caches")
        clobApiCache.clear()
        fastTradingClobApiCache.clear()
        rpcApiCache.clear()
        builderRelayerApiCache.clear()
    }
    
    fun clearClobApiCache(walletAddress: String) {
        clobApiCache.keys.removeIf { it.walletAddress == walletAddress }
        fastTradingClobApiCache.keys.removeIf { it.walletAddress == walletAddress }
        logger.debug("Cleared CLOB API cache: {}", walletAddress)
    }
    
    fun clearRpcApiCache(rpcUrl: String) {
        val actualRpcUrl = if (rpcUrl.endsWith("/")) rpcUrl else "$rpcUrl/"
        rpcApiCache.remove(actualRpcUrl)
        logger.debug("Cleared RPC API cache: {}", actualRpcUrl)
    }
}

class RpcUrlReplaceInterceptor(
    private val fixedBaseUrl: String,
    private val actualRpcUrl: String
) : Interceptor {
    @Throws(IOException::class)
    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        val originalUrl = originalRequest.url
        val originalUrlString = originalUrl.toString()
        val newUrlString = originalUrlString.replace(fixedBaseUrl, actualRpcUrl)
        val newUrl = newUrlString.toHttpUrlOrNull()
            ?: throw IllegalArgumentException("无效的 RPC URL: $newUrlString")
        
        val newRequest = originalRequest.newBuilder()
            .url(newUrl)
            .build()
        
        return chain.proceed(newRequest)
    }
}

class ResponseLoggingInterceptor : Interceptor {
    private val logger = LoggerFactory.getLogger(ResponseLoggingInterceptor::class.java)
    
    @Throws(IOException::class)
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)
        if (response.isSuccessful) {
            try {
                val responseBody = response.peekBody(2048)
                val responseBodyString = responseBody.string()
                val isEmpty = responseBodyString.isBlank()
                val trimmedBody = responseBodyString.trim()
                val isJson = !isEmpty && (
                    trimmedBody.startsWith("{") || 
                    trimmedBody.startsWith("[")
                )
                if (isEmpty || !isJson) {
                    val bodyPreview = if (isEmpty) {
                        "(empty response body)"
                    } else {
                        trimmedBody.take(500)
                    }
                    logger.warn(
                        "API response format is unexpected: method=${request.method}, url=${request.url}, " +
                        "code=${response.code}, isJson=$isJson, isEmpty=$isEmpty, " +
                        "responseBody=$bodyPreview"
                    )
                }
            } catch (e: Exception) {
                logger.debug("Failed to inspect API response body: ${e.message}")
            }
        }
        
        return response
    }
}
