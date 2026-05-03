package com.wrbug.polymarketbot.repository

import com.wrbug.polymarketbot.entity.BacktestTask
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository

@Repository
interface BacktestTaskRepository : JpaRepository<BacktestTask, Long> {

    fun findByLeaderId(leaderId: Long): List<BacktestTask>

    fun findByStatus(status: String): List<BacktestTask>

    fun findByLeaderIdAndStatus(leaderId: Long, status: String): List<BacktestTask>

    @Query("SELECT t FROM BacktestTask t WHERE t.leaderId = :leaderId AND t.status = :status ORDER BY t.profitRate DESC")
    fun findByLeaderIdAndStatusOrderByProfitRateDesc(leaderId: Long, status: String): List<BacktestTask>

    @Query("SELECT t FROM BacktestTask t WHERE t.status = :status ORDER BY t.createdAt DESC")
    fun findByStatusOrderByCreatedAtDesc(status: String): List<BacktestTask>

    @Modifying
    @Query("UPDATE BacktestTask t SET t.status = :status, t.updatedAt = :updatedAt WHERE t.id = :id")
    fun updateStatus(id: Long, status: String, updatedAt: Long = System.currentTimeMillis())

    @Modifying
    @Query("UPDATE BacktestTask t SET t.status = :status, t.errorMessage = :errorMessage, t.updatedAt = :updatedAt WHERE t.id = :id")
    fun updateStatusAndError(id: Long, status: String, errorMessage: String?, updatedAt: Long = System.currentTimeMillis())

    @Modifying
    @Query("UPDATE BacktestTask t SET t.progress = :progress, t.updatedAt = :updatedAt WHERE t.id = :id")
    fun updateProgress(id: Long, progress: Int, updatedAt: Long = System.currentTimeMillis())
}

