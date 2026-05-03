package com.wrbug.polymarketbot.repository

import com.wrbug.polymarketbot.entity.SystemConfig
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface SystemConfigRepository : JpaRepository<SystemConfig, Long> {
    fun findByConfigKey(configKey: String): SystemConfig?
}

