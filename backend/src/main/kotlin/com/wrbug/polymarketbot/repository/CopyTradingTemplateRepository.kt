package com.wrbug.polymarketbot.repository

import com.wrbug.polymarketbot.entity.CopyTradingTemplate
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface CopyTradingTemplateRepository : JpaRepository<CopyTradingTemplate, Long> {
    
    fun findByTemplateName(templateName: String): CopyTradingTemplate?
    
    fun existsByTemplateName(templateName: String): Boolean
    
    fun findAllByOrderByCreatedAtDesc(): List<CopyTradingTemplate>
}

