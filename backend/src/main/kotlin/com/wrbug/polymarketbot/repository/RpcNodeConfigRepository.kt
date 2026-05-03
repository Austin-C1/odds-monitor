package com.wrbug.polymarketbot.repository

import com.wrbug.polymarketbot.entity.RpcNodeConfig
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface RpcNodeConfigRepository : JpaRepository<RpcNodeConfig, Long> {
    fun findAllByEnabledTrueOrderByPriorityAsc(): List<RpcNodeConfig>
    
    fun findByIdAndEnabledTrue(id: Long): RpcNodeConfig?
    
    fun findAllByOrderByPriorityAsc(): List<RpcNodeConfig>
}
