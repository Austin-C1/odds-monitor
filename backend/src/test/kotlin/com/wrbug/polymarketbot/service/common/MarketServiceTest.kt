package com.wrbug.polymarketbot.service.common

import com.wrbug.polymarketbot.api.MarketResponse
import com.wrbug.polymarketbot.api.PolymarketGammaApi
import com.wrbug.polymarketbot.repository.MarketRepository
import com.wrbug.polymarketbot.util.RetrofitFactory
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import retrofit2.Response

class MarketServiceTest {

    private val marketRepository = mock(MarketRepository::class.java)
    private val retrofitFactory = mock(RetrofitFactory::class.java)
    private val gammaApi = mock(PolymarketGammaApi::class.java)

    private val service = MarketService(
        marketRepository = marketRepository,
        retrofitFactory = retrofitFactory
    )

    @Test
    fun `getNegRiskByConditionId caches repeated lookups`() = runTest {
        `when`(retrofitFactory.createGammaApi()).thenReturn(gammaApi)
        `when`(
            gammaApi.listMarkets(
                listOf("condition-1"),
                null,
                null
            )
        ).thenReturn(
            Response.success(
                listOf(
                    MarketResponse(
                        conditionId = "condition-1",
                        negRisk = true
                    )
                )
            )
        )

        val first = service.getNegRiskByConditionId("condition-1")
        val second = service.getNegRiskByConditionId("condition-1")

        assertEquals(true, first)
        assertEquals(true, second)
        verify(gammaApi, times(1)).listMarkets(listOf("condition-1"), null, null)
    }
}
