package com.wrbug.polymarketbot.repository

import com.wrbug.polymarketbot.entity.NotificationConfig
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface NotificationConfigRepository : JpaRepository<NotificationConfig, Long> {
    fun findByType(type: String): List<NotificationConfig>
    fun findByTypeAndEnabled(type: String, enabled: Boolean): List<NotificationConfig>
    fun findByIdAndType(id: Long, type: String): NotificationConfig?
}

