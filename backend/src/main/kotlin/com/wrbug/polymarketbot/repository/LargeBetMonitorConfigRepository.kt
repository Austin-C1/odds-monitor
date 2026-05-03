package com.wrbug.polymarketbot.repository

import com.wrbug.polymarketbot.entity.LargeBetMonitorConfig
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface LargeBetMonitorConfigRepository : JpaRepository<LargeBetMonitorConfig, Long>
