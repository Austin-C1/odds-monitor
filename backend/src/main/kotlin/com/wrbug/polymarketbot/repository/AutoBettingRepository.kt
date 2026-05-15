package com.wrbug.polymarketbot.repository

import com.wrbug.polymarketbot.entity.AutoBettingIntent
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface AutoBettingIntentRepository : JpaRepository<AutoBettingIntent, Long> {
    fun existsByDedupeKeyAndStatusIn(dedupeKey: String, statuses: Collection<String>): Boolean
    fun findTop100ByOrderByCreatedAtDesc(): List<AutoBettingIntent>
    fun findTop100ByStatusAndCrownHistoryVerifiedTrueOrderByCreatedAtDesc(status: String): List<AutoBettingIntent>
}
