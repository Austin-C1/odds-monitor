package com.wrbug.polymarketbot.service.common

import com.wrbug.polymarketbot.api.FeeRateResponse
import com.wrbug.polymarketbot.api.OrderbookEntry
import com.wrbug.polymarketbot.api.OrderbookResponse
import com.wrbug.polymarketbot.api.PolymarketClobApi
import com.wrbug.polymarketbot.util.RetrofitFactory
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import retrofit2.Response

class PolymarketClobServiceTest {

    private val clobApi = mock(PolymarketClobApi::class.java)
    private val retrofitFactory = mock(RetrofitFactory::class.java)
    private val fastClobApi = mock(PolymarketClobApi::class.java)

    private val service = PolymarketClobService(
        clobApi = clobApi,
        retrofitFactory = retrofitFactory
    )

    @Test
    fun `getFeeRate caches token fee for repeated calls`() = runTest {
        `when`(clobApi.getFeeRate("token-1")).thenReturn(Response.success(FeeRateResponse(baseFee = 42)))

        val first = service.getFeeRate("token-1")
        val second = service.getFeeRate("token-1")

        assertTrue(first.isSuccess)
        assertTrue(second.isSuccess)
        assertEquals(42, first.getOrNull())
        assertEquals(42, second.getOrNull())
        verify(clobApi, times(1)).getFeeRate("token-1")
    }

    @Test
    fun `getFastOrderbookByTokenId caches token orderbook for repeated calls`() = runTest {
        `when`(retrofitFactory.createFastTradingClobApiWithoutAuth()).thenReturn(fastClobApi)
        `when`(fastClobApi.getOrderbook("token-1", null)).thenReturn(
            Response.success(
                OrderbookResponse(
                    bids = listOf(OrderbookEntry(price = "0.61", size = "10")),
                    asks = listOf(OrderbookEntry(price = "0.62", size = "12"))
                )
            )
        )

        val first = service.getFastOrderbookByTokenId("token-1")
        val second = service.getFastOrderbookByTokenId("token-1")

        assertTrue(first.isSuccess)
        assertTrue(second.isSuccess)
        assertEquals(first.getOrNull(), second.getOrNull())
        verify(fastClobApi, times(1)).getOrderbook("token-1", null)
    }
}
