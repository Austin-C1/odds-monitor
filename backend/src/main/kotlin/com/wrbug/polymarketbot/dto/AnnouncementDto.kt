package com.wrbug.polymarketbot.dto

data class AnnouncementListRequest(
    val forceRefresh: Boolean = false
)

data class AnnouncementDetailRequest(
    val id: Long? = null,
    val forceRefresh: Boolean = false
)

data class ReactionsDto(
    val plusOne: Int = 0,
    val minusOne: Int = 0,
    val laugh: Int = 0,
    val confused: Int = 0,
    val heart: Int = 0,
    val hooray: Int = 0,
    val eyes: Int = 0,
    val rocket: Int = 0,
    val total: Int = 0
)

data class AnnouncementDto(
    val id: Long,
    val title: String,
    val body: String,
    val author: String,
    val authorAvatarUrl: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val reactions: ReactionsDto? = null
)

data class AnnouncementListResponse(
    val list: List<AnnouncementDto>,
    val hasMore: Boolean,
    val total: Int
)

