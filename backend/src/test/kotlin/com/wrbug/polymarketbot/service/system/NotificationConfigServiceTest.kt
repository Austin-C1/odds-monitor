package com.wrbug.polymarketbot.service.system

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.wrbug.polymarketbot.dto.NotificationConfigData
import com.wrbug.polymarketbot.dto.NotificationConfigRequest
import com.wrbug.polymarketbot.entity.NotificationConfig
import com.wrbug.polymarketbot.repository.NotificationConfigRepository
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class NotificationConfigServiceTest {
    private val repository = mock(NotificationConfigRepository::class.java)
    private val service = NotificationConfigService(repository, jacksonObjectMapper())

    @Test
    fun `betting success telegram config is validated and parsed as telegram config`() = runBlocking {
        val captor = ArgumentCaptor.forClass(NotificationConfig::class.java)
        `when`(repository.save(captor.capture())).thenAnswer { invocation ->
            (invocation.arguments[0] as NotificationConfig).copy(id = 9L)
        }

        val result = service.createConfig(
            NotificationConfigRequest(
                type = BETTING_SUCCESS_TELEGRAM_TYPE,
                name = "投注成功机器人",
                enabled = true,
                config = mapOf(
                    "botToken" to "bot-token",
                    "chatIds" to listOf("-1001")
                )
            )
        )

        assertTrue(result.isSuccess)
        val dto = result.getOrThrow()
        assertEquals(BETTING_SUCCESS_TELEGRAM_TYPE, dto.type)
        val telegramConfig = dto.config as NotificationConfigData.Telegram
        assertEquals("bot-token", telegramConfig.data.botToken)
        assertEquals(listOf("-1001"), telegramConfig.data.chatIds)
    }
}
