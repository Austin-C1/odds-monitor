package com.wrbug.polymarketbot.util

import com.google.gson.Gson
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Test

class RetrofitFactoryCredentialCacheTest {

    @Test
    fun `different credentials on the same wallet do not reuse the same authenticated CLOB client`() {
        val factory = RetrofitFactory(Gson())

        val first = factory.createClobApi(
            apiKey = "api-key-1",
            apiSecret = "api-secret-1",
            apiPassphrase = "api-passphrase-1",
            walletAddress = "0x0000000000000000000000000000000000000001"
        )
        val second = factory.createClobApi(
            apiKey = "api-key-2",
            apiSecret = "api-secret-2",
            apiPassphrase = "api-passphrase-2",
            walletAddress = "0x0000000000000000000000000000000000000001"
        )

        assertNotSame(first, second)
    }

    @Test
    fun `clearing a wallet cache invalidates both standard and fast trading clients`() {
        val factory = RetrofitFactory(Gson())
        val walletAddress = "0x0000000000000000000000000000000000000001"

        val standard = factory.createClobApi("api-key", "api-secret", "api-passphrase", walletAddress)
        val fast = factory.createFastTradingClobApi("api-key", "api-secret", "api-passphrase", walletAddress)

        factory.clearClobApiCache(walletAddress)

        val standardAfterClear = factory.createClobApi("api-key", "api-secret", "api-passphrase", walletAddress)
        val fastAfterClear = factory.createFastTradingClobApi("api-key", "api-secret", "api-passphrase", walletAddress)

        assertNotSame(standard, standardAfterClear)
        assertNotSame(fast, fastAfterClear)
    }
}
