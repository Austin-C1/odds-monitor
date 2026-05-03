package com.wrbug.polymarketbot.service.accounts

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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.springframework.context.ApplicationEventPublisher
import java.util.Optional

class AccountServiceDeleteTest {

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
    fun `deleteAccount deletes account and publishes refresh event`() {
        val accountId = 12L
        val account = Account(
            id = accountId,
            privateKey = "encrypted-private-key",
            walletAddress = "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd",
            proxyAddress = "0x1234567890abcdef1234567890abcdef12345678"
        )

        `when`(accountRepository.findById(accountId)).thenReturn(Optional.of(account))

        val result = service.deleteAccount(accountId)

        assertTrue(result.isSuccess)
        verify(accountRepository).delete(account)
        verify(eventPublisher).publishEvent(any())
    }
}
