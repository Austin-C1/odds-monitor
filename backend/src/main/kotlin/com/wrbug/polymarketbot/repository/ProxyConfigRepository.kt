package com.wrbug.polymarketbot.repository

import com.wrbug.polymarketbot.entity.ProxyConfig
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface ProxyConfigRepository : JpaRepository<ProxyConfig, Long> {
    fun findByEnabledTrue(): ProxyConfig?
    
    fun findByTypeAndEnabledTrue(type: String): ProxyConfig?
    
    fun findByType(type: String): ProxyConfig?
}

