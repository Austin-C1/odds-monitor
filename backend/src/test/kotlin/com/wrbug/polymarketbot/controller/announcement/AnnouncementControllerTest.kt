package com.wrbug.polymarketbot.controller.announcement

import com.wrbug.polymarketbot.dto.AnnouncementDetailRequest
import com.wrbug.polymarketbot.dto.AnnouncementDto
import com.wrbug.polymarketbot.dto.AnnouncementListResponse
import com.wrbug.polymarketbot.service.announcement.AnnouncementService
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.springframework.context.support.StaticMessageSource

class AnnouncementControllerTest {

    private val announcementService = mock(AnnouncementService::class.java)
    private val messageSource = StaticMessageSource()
    private val controller = AnnouncementController(announcementService, messageSource)

    @Test
    fun `getAnnouncementList accepts missing request body`() = runBlocking {
        val responseBody = AnnouncementListResponse(
            list = emptyList(),
            hasMore = false,
            total = 0
        )
        `when`(announcementService.getAnnouncementList(false)).thenReturn(Result.success(responseBody))

        val response = controller.getAnnouncementList(null)

        assertEquals(200, response.statusCode.value())
        assertNotNull(response.body)
        assertEquals(0, response.body!!.code)
        assertEquals(responseBody, response.body!!.data)
    }

    @Test
    fun `getAnnouncementDetail accepts empty request body and defaults to latest announcement`() = runBlocking {
        val announcement = AnnouncementDto(
            id = 1L,
            title = "Latest announcement",
            body = "body",
            author = "WrBug",
            authorAvatarUrl = null,
            createdAt = 1L,
            updatedAt = 2L,
            reactions = null
        )
        `when`(announcementService.getAnnouncementDetail(null, false)).thenReturn(Result.success(announcement))

        val response = controller.getAnnouncementDetail(AnnouncementDetailRequest())

        assertEquals(200, response.statusCode.value())
        assertNotNull(response.body)
        assertEquals(0, response.body!!.code)
        assertEquals(announcement, response.body!!.data)
    }
}
