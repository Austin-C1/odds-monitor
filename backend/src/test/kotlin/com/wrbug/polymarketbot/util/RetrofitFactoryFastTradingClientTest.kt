package com.wrbug.polymarketbot.util

import com.google.gson.Gson
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test

class RetrofitFactoryFastTradingClientTest {

    @Test
    fun `fast trading CLOB clients exist and use short timeouts`() {
        val factory = RetrofitFactory(Gson())
        val withoutAuthMethod = RetrofitFactory::class.java.methods.firstOrNull {
            it.name == "createFastTradingClobApiWithoutAuth" && it.parameterCount == 0
        } ?: fail("RetrofitFactory should expose createFastTradingClobApiWithoutAuth()")
        val authMethod = RetrofitFactory::class.java.methods.firstOrNull {
            it.name == "createFastTradingClobApi" && it.parameterCount == 4
        } ?: fail("RetrofitFactory should expose createFastTradingClobApi(apiKey, apiSecret, apiPassphrase, walletAddress)")

        withoutAuthMethod.invoke(factory)
        authMethod.invoke(factory, "api-key", "api-secret", "api-passphrase", "0x0000000000000000000000000000000000000001")

        val standardClient = createClient().build()
        val fastClient = factory.createFastTradingClientBuilder().build()

        assertEquals(30_000, standardClient.connectTimeoutMillis.toLong())
        assertEquals(30_000, standardClient.readTimeoutMillis.toLong())
        assertEquals(30_000, standardClient.writeTimeoutMillis.toLong())
        assertEquals(3_000, fastClient.connectTimeoutMillis.toLong())
        assertEquals(5_000, fastClient.readTimeoutMillis.toLong())
        assertEquals(3_000, fastClient.writeTimeoutMillis.toLong())
    }
}
