package com.wrbug.polymarketbot.controller.system

import com.wrbug.polymarketbot.dto.TestNotificationRequest
import com.wrbug.polymarketbot.service.system.NotificationConfigService
import com.wrbug.polymarketbot.service.system.NotificationTemplateService
import com.wrbug.polymarketbot.service.system.TelegramNotificationService
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.mockito.Mockito.verify
import org.springframework.context.support.StaticMessageSource

class NotificationControllerTest {

    private val notificationConfigService = mock(NotificationConfigService::class.java)
    private val telegramNotificationService = mock(TelegramNotificationService::class.java)
    private val notificationTemplateService = mock(NotificationTemplateService::class.java)
    private val messageSource = StaticMessageSource()

    private val controller = NotificationController(
        notificationConfigService = notificationConfigService,
        telegramNotificationService = telegramNotificationService,
        notificationTemplateService = notificationTemplateService,
        messageSource = messageSource
    )

    @Test
    fun `test notification should target the selected config when config id is provided`() {
        runBlocking {
            `when`(telegramNotificationService.sendTestMessage("hello", 7L)).thenReturn(true)
        }

        val response = controller.testNotification(TestNotificationRequest(configId = 7L, message = "hello"))

        assertEquals(0, response.body?.code)
        assertEquals(true, response.body?.data)
        runBlocking {
            verify(telegramNotificationService).sendTestMessage("hello", 7L)
        }
    }
}
