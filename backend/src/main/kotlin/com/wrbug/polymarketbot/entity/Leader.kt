package com.wrbug.polymarketbot.entity

import jakarta.persistence.*
import com.wrbug.polymarketbot.util.CategoryValidator

@Entity
@Table(name = "copy_trading_leaders")
data class Leader(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,
    
    @Column(name = "leader_address", unique = true, nullable = false, length = 42)
    val leaderAddress: String,
    
    @Column(name = "leader_name", length = 100)
    val leaderName: String? = null,
    
    @Column(name = "category", length = 20)
    val category: String? = null,

    @Column(name = "custom_group", length = 100)
    val customGroup: String? = null,
    
    @Column(name = "remark", columnDefinition = "TEXT")
    val remark: String? = null,
    
    @Column(name = "website", length = 500)
    val website: String? = null,
    
    @Column(name = "created_at", nullable = false)
    val createdAt: Long = System.currentTimeMillis(),
    
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Long = System.currentTimeMillis()
) {
    init {
        if (category != null) {
            CategoryValidator.validate(category)
        }
    }
}

