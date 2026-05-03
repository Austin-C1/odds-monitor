package com.wrbug.polymarketbot.repository

import com.wrbug.polymarketbot.entity.Leader
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

/**
 * Leader Repository
 */
@Repository
interface LeaderRepository : JpaRepository<Leader, Long> {
    
    fun findByLeaderAddress(leaderAddress: String): Leader?
    
    fun existsByLeaderAddress(leaderAddress: String): Boolean
    
    fun findByCategory(category: String?): List<Leader>
    
    fun findAllByOrderByCreatedAtAsc(): List<Leader>
}

