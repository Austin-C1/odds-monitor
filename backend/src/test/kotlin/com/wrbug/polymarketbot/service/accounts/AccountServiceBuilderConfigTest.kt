package com.wrbug.polymarketbot.service.accounts

import com.wrbug.polymarketbot.dto.AccountUpdateRequest
import com.wrbug.polymarketbot.entity.Account
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
import org.springframework.context.ApplicationEventPublisher
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import java.util.Optional

class AccountServiceBuilderConfigTest {

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
    fun `updateAccount should persist and expose account-level builder config`() = runTest {
        val account = Account(
            id = 9L,
            privateKey = "encrypted-private-key",
            walletAddress = "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd",
            proxyAddress = "0x1234567890abcdef1234567890abcdef12345678",
            accountName = "Old Name",
            remark = "Old Remark",
            autoRedeemEnabled = false
        )

        `when`(accountRepository.findById(9L)).thenReturn(Optional.of(account))
        `when`(cryptoUtils.encrypt("builder-api-key")).thenReturn("enc-api-key")
        `when`(cryptoUtils.encrypt("builder-secret")).thenReturn("enc-secret")
        `when`(cryptoUtils.encrypt("builder-passphrase")).thenReturn("enc-passphrase")
        `when`(cryptoUtils.decrypt("enc-api-key")).thenReturn("builder-api-key")
        `when`(cryptoUtils.decrypt("enc-secret")).thenReturn("builder-secret")
        `when`(cryptoUtils.decrypt("enc-passphrase")).thenReturn("builder-passphrase")
        `when`(accountRepository.save(any(Account::class.java))).thenAnswer { it.arguments[0] as Account }

        val result = service.updateAccount(
            AccountUpdateRequest(
                accountId = 9L,
                accountName = "New Name",
                remark = "New Remark",
                builderApiKey = "builder-api-key",
                builderSecret = "builder-secret",
                builderPassphrase = "builder-passphrase",
                autoRedeemEnabled = true
            )
        )

        assertTrue(result.isSuccess)
        val dto = result.getOrThrow()
        assertEquals("New Name", dto.accountName)
        assertEquals("New Remark", dto.remark)
        assertTrue(dto.builderConfigured)
        assertEquals("builder-api-key", dto.builderApiKeyDisplay)
        assertEquals("builder-secret", dto.builderSecretDisplay)
        assertEquals("builder-passphrase", dto.builderPassphraseDisplay)
        assertTrue(dto.autoRedeemEnabled)

        verify(cryptoUtils).encrypt("builder-api-key")
        verify(cryptoUtils).encrypt("builder-secret")
        verify(cryptoUtils).encrypt("builder-passphrase")
    }
}
