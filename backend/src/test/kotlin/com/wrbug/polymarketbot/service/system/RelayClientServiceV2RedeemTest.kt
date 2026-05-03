package com.wrbug.polymarketbot.service.system

import com.wrbug.polymarketbot.constants.PolymarketConstants
import com.wrbug.polymarketbot.repository.AccountRepository
import com.wrbug.polymarketbot.util.CryptoUtils
import com.wrbug.polymarketbot.util.EthereumUtils
import com.wrbug.polymarketbot.util.RetrofitFactory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import java.math.BigInteger

class RelayClientServiceV2RedeemTest {

    private val relayClientService = RelayClientService(
        retrofitFactory = mock(RetrofitFactory::class.java),
        accountRepository = mock(AccountRepository::class.java),
        cryptoUtils = mock(CryptoUtils::class.java),
        systemConfigService = mock(SystemConfigService::class.java),
        rpcNodeService = mock(RpcNodeService::class.java)
    )

    @Test
    fun `neg risk redeem uses adapter contract and two-argument selector`() {
        val tx = relayClientService.createRedeemTx(
            conditionId = "0x" + "1".repeat(64),
            indexSets = listOf(BigInteger.ONE, BigInteger.TWO),
            isNegRisk = true
        )

        val selector = EthereumUtils.getFunctionSelector("redeemPositions(bytes32,uint256[])").removePrefix("0x")

        assertEquals(PolymarketConstants.NEG_RISK_ADAPTER_ADDRESS, tx.to)
        assertTrue(tx.data.startsWith("0x$selector"))
    }

    @Test
    fun `regular redeem keeps conditional tokens contract`() {
        val tx = relayClientService.createRedeemTx(
            conditionId = "0x" + "2".repeat(64),
            indexSets = listOf(BigInteger.ONE),
            isNegRisk = false
        )

        val selector = EthereumUtils.getFunctionSelector("redeemPositions(address,bytes32,bytes32,uint256[])").removePrefix("0x")

        assertEquals(PolymarketConstants.CTF_CONTRACT_ADDRESS, tx.to)
        assertTrue(tx.data.startsWith("0x$selector"))
    }

    @Test
    fun `unwrap uses wrapped collateral contract`() {
        val tx = relayClientService.createUnwrapWcolTx(
            toAddress = "0x" + "3".repeat(40),
            amountWei = BigInteger.TEN
        )

        assertEquals(PolymarketConstants.NEG_RISK_WRAPPED_COLLATERAL_ADDRESS, tx.to)
    }
}
