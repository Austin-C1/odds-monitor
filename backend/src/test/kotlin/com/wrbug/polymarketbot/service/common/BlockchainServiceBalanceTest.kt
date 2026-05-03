package com.wrbug.polymarketbot.service.common

import com.google.gson.Gson
import com.google.gson.JsonPrimitive
import com.wrbug.polymarketbot.api.EthereumRpcApi
import com.wrbug.polymarketbot.api.JsonRpcRequest
import com.wrbug.polymarketbot.api.JsonRpcResponse
import com.wrbug.polymarketbot.constants.PolymarketConstants
import com.wrbug.polymarketbot.service.system.RelayClientService
import com.wrbug.polymarketbot.service.system.RpcNodeService
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import retrofit2.Response

class BlockchainServiceBalanceTest {

    private val retrofitFactory = mock(com.wrbug.polymarketbot.util.RetrofitFactory::class.java)
    private val relayClientService = mock(RelayClientService::class.java)
    private val rpcNodeService = mock(RpcNodeService::class.java)

    private val service = BlockchainService(
        retrofitFactory = retrofitFactory,
        relayClientService = relayClientService,
        rpcNodeService = rpcNodeService,
        gson = Gson()
    )

    @Test
    fun `getUsdcBalance includes legacy USDC balance when pUSD balance is zero`() = runTest {
        val rpcApi = object : EthereumRpcApi {
            override suspend fun call(request: JsonRpcRequest): Response<JsonRpcResponse> {
                val call = request.params.first() as Map<*, *>
                val contract = (call["to"] as String).lowercase()
                val hexBalance = when (contract) {
                    PolymarketConstants.PUSD_CONTRACT_ADDRESS.lowercase() -> "0x0"
                    PolymarketConstants.USDC_CONTRACT_ADDRESS.lowercase() -> "0x989680"
                    else -> error("unexpected contract $contract")
                }
                return Response.success(JsonRpcResponse(result = JsonPrimitive(hexBalance)))
            }
        }

        `when`(rpcNodeService.getHttpUrl()).thenReturn("https://polygon.publicnode.com")
        `when`(retrofitFactory.createEthereumRpcApi("https://polygon.publicnode.com")).thenReturn(rpcApi)

        val balance = service.getUsdcBalance(
            walletAddress = "0x1111111111111111111111111111111111111111",
            proxyAddress = "0x1111111111111111111111111111111111111111"
        ).getOrThrow()

        assertEquals("10", balance)
    }
}
