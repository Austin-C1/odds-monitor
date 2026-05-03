package com.wrbug.polymarketbot.service.accounts

import com.wrbug.polymarketbot.entity.Account
import com.wrbug.polymarketbot.constants.PolymarketConstants
import com.wrbug.polymarketbot.enums.WalletType
import com.wrbug.polymarketbot.repository.AccountRepository
import com.wrbug.polymarketbot.service.common.BlockchainService
import com.wrbug.polymarketbot.service.common.MarketService
import com.wrbug.polymarketbot.service.common.PolymarketApiKeyService
import com.wrbug.polymarketbot.service.common.PolymarketClobService
import com.wrbug.polymarketbot.service.copytrading.orders.OrderSigningService
import com.wrbug.polymarketbot.service.system.RelayClientService
import com.wrbug.polymarketbot.service.system.TelegramNotificationService
import com.wrbug.polymarketbot.util.CryptoUtils
import com.wrbug.polymarketbot.util.JsonUtils
import com.wrbug.polymarketbot.util.RetrofitFactory
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.springframework.context.ApplicationEventPublisher
import java.math.BigInteger
import java.util.Optional

class AccountServiceV2SetupTest {

    private val unlimitedAllowance = BigInteger("115792089237316195423570985008687907853269984665640564039457584007913129639935")

    private val accountRepository = mock(AccountRepository::class.java)
    private val clobService = mock(PolymarketClobService::class.java)
    private val retrofitFactory = mock(RetrofitFactory::class.java)
    private val blockchainService = mock(BlockchainService::class.java)
    private val apiKeyService = mock(PolymarketApiKeyService::class.java)
    private val orderSigningService = mock(OrderSigningService::class.java)
    private val cryptoUtils = mock(CryptoUtils::class.java)
    private val marketService = mock(MarketService::class.java)
    private val telegramNotificationService = mock(TelegramNotificationService::class.java)
    private val relayClientService = mock(RelayClientService::class.java)
    private val jsonUtils = mock(JsonUtils::class.java)
    private val eventPublisher = mock(ApplicationEventPublisher::class.java)

    private val service = AccountService(
        accountRepository = accountRepository,
        clobService = clobService,
        retrofitFactory = retrofitFactory,
        blockchainService = blockchainService,
        apiKeyService = apiKeyService,
        orderSigningService = orderSigningService,
        cryptoUtils = cryptoUtils,
        marketService = marketService,
        telegramNotificationService = telegramNotificationService,
        relayClientService = relayClientService,
        jsonUtils = jsonUtils,
        eventPublisher = eventPublisher
    )

    @Test
    fun `checkAccountSetupStatus should use current USDC approval spenders`() = runTest {
        val account = demoAccount()
        `when`(accountRepository.findById(account.id!!)).thenReturn(Optional.of(account))
        `when`(blockchainService.isProxyDeployed(account.proxyAddress)).thenReturn(true)
        `when`(blockchainService.getUsdcAllowance(account.proxyAddress, PolymarketConstants.CTF_CONTRACT_ADDRESS))
            .thenReturn(Result.success(unlimitedAllowance))
        `when`(blockchainService.getUsdcAllowance(account.proxyAddress, PolymarketConstants.CTF_EXCHANGE_V2_ADDRESS))
            .thenReturn(Result.success(unlimitedAllowance))
        `when`(blockchainService.getUsdcAllowance(account.proxyAddress, PolymarketConstants.NEG_RISK_CTF_EXCHANGE_V2_ADDRESS))
            .thenReturn(Result.success(unlimitedAllowance))
        `when`(blockchainService.getUsdcAllowance(account.proxyAddress, PolymarketConstants.NEG_RISK_ADAPTER_ADDRESS))
            .thenReturn(Result.success(unlimitedAllowance))

        val result = service.checkAccountSetupStatus(account.id!!)

        assertTrue(result.isSuccess)
        val body = result.getOrThrow()
        assertTrue(body.tokensApproved)
        assertEquals("unlimited", body.approvalDetails?.get("CTF_CONTRACT"))
        assertEquals("unlimited", body.approvalDetails?.get("CTF_EXCHANGE"))
        assertEquals("unlimited", body.approvalDetails?.get("NEG_RISK_EXCHANGE"))
        assertEquals("unlimited", body.approvalDetails?.get("NEG_RISK_ADAPTER"))
    }

    @Test
    fun `executeSetupStep step 3 should build approval transactions with account builder config`() = runTest {
        val account = demoAccount()
        val approveTx1 = RelayClientService.SafeTransaction(to = "ctf", data = "0x1")
        val approveTx2 = RelayClientService.SafeTransaction(to = "exchange", data = "0x2")
        val approveTx3 = RelayClientService.SafeTransaction(to = "neg-risk-exchange", data = "0x3")
        val approveTx4 = RelayClientService.SafeTransaction(to = "neg-risk-adapter", data = "0x4")
        val multisendTx = RelayClientService.SafeTransaction(to = "multi", data = "0x5")
        val builderCredentials = RelayClientService.BuilderCredentials(
            apiKey = "builder-api",
            secret = "builder-secret",
            passphrase = "builder-passphrase"
        )

        `when`(accountRepository.findById(account.id!!)).thenReturn(Optional.of(account))
        `when`(cryptoUtils.decrypt(account.privateKey)).thenReturn("private-key")
        `when`(cryptoUtils.decrypt(account.builderApiKey!!)).thenReturn(builderCredentials.apiKey)
        `when`(cryptoUtils.decrypt(account.builderSecret!!)).thenReturn(builderCredentials.secret)
        `when`(cryptoUtils.decrypt(account.builderPassphrase!!)).thenReturn(builderCredentials.passphrase)
        `when`(relayClientService.createUsdcApproveTx(PolymarketConstants.CTF_CONTRACT_ADDRESS, unlimitedAllowance))
            .thenReturn(approveTx1)
        `when`(relayClientService.createUsdcApproveTx(PolymarketConstants.CTF_EXCHANGE_V2_ADDRESS, unlimitedAllowance))
            .thenReturn(approveTx2)
        `when`(relayClientService.createUsdcApproveTx(PolymarketConstants.NEG_RISK_CTF_EXCHANGE_V2_ADDRESS, unlimitedAllowance))
            .thenReturn(approveTx3)
        `when`(relayClientService.createUsdcApproveTx(PolymarketConstants.NEG_RISK_ADAPTER_ADDRESS, unlimitedAllowance))
            .thenReturn(approveTx4)
        `when`(relayClientService.createMultiSendTx(listOf(approveTx1, approveTx2, approveTx3, approveTx4)))
            .thenReturn(multisendTx)
        `when`(
            relayClientService.execute(
                privateKey = "private-key",
                proxyAddress = account.proxyAddress,
                safeTx = multisendTx,
                walletType = WalletType.MAGIC,
                builderCredentials = builderCredentials
            )
        ).thenReturn(Result.success("0xtx"))

        val result = service.executeSetupStep(account.id!!, 3)

        assertTrue(result.isSuccess)
        assertEquals("0xtx", result.getOrThrow().transactionHash)
        verify(relayClientService).createUsdcApproveTx(PolymarketConstants.CTF_CONTRACT_ADDRESS, unlimitedAllowance)
        verify(relayClientService).createUsdcApproveTx(PolymarketConstants.CTF_EXCHANGE_V2_ADDRESS, unlimitedAllowance)
        verify(relayClientService).createUsdcApproveTx(PolymarketConstants.NEG_RISK_CTF_EXCHANGE_V2_ADDRESS, unlimitedAllowance)
        verify(relayClientService).createUsdcApproveTx(PolymarketConstants.NEG_RISK_ADAPTER_ADDRESS, unlimitedAllowance)
    }

    private fun demoAccount() = Account(
        id = 7L,
        privateKey = "encrypted-private-key",
        walletAddress = "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd",
        proxyAddress = "0x1234567890abcdef1234567890abcdef12345678",
        apiKey = "api-key",
        apiSecret = "encrypted-secret",
        apiPassphrase = "encrypted-passphrase",
        builderApiKey = "encrypted-builder-api",
        builderSecret = "encrypted-builder-secret",
        builderPassphrase = "encrypted-builder-passphrase",
        walletType = "magic"
    )
}
