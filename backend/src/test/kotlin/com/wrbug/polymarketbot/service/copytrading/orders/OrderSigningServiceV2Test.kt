package com.wrbug.polymarketbot.service.copytrading.orders

import com.google.gson.Gson
import com.wrbug.polymarketbot.api.NewOrderRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OrderSigningServiceV2Test {

    private val service = OrderSigningService()
    private val gson = Gson()

    @Test
    fun `exchange contracts should use official v2 addresses`() {
        assertEquals("0xE111180000d2663C0091e4f400237545B87B996B", service.getExchangeContract(false))
        assertEquals("0xe2222d279d744050d28e00520010520000310F59", service.getExchangeContract(true))
    }

    @Test
    fun `createAndSignOrder should produce v2 wire payload`() {
        val order = service.createAndSignOrder(
            privateKey = "0x59c6995e998f97a5a0044976f4d9d60b3b17ee6d2e5c7e4f3a2a6b5f6c7d8e9f",
            makerAddress = "0x1111111111111111111111111111111111111111",
            tokenId = "123456789",
            side = "BUY",
            price = "0.55",
            size = "10",
            signatureType = 2,
            exchangeContract = service.getExchangeContract(false)
        )

        assertEquals(null, order.taker)
        assertEquals(null, order.expiration)
        assertNotNull(order.timestamp)
        assertTrue(order.timestamp!!.toLong() > 0)
        assertEquals("0x0000000000000000000000000000000000000000000000000000000000000000", order.metadata)
        assertEquals("0x0000000000000000000000000000000000000000000000000000000000000000", order.builder)
        assertEquals(null, order.nonce)
        assertEquals(null, order.feeRateBps)
        assertTrue(order.signature.startsWith("0x"))

        val json = gson.toJson(
            NewOrderRequest(
                order = order,
                owner = "api-key",
                orderType = "FAK"
            )
        )

        assertTrue(json.contains("\"timestamp\""))
        assertTrue(json.contains("\"metadata\""))
        assertTrue(json.contains("\"builder\""))
        assertFalse(json.contains("feeRateBps"))
        assertFalse(json.contains("\"nonce\""))
        assertFalse(json.contains("\"taker\""))
        assertFalse(json.contains("\"expiration\""))
    }
}
