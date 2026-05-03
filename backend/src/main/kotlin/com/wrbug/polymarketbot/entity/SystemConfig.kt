package com.wrbug.polymarketbot.entity

import jakarta.persistence.*

@Entity
@Table(name = "system_config")
data class SystemConfig(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,
    
    @Column(name = "config_key", unique = true, nullable = false, length = 100)
    val configKey: String,
    
    @Column(name = "config_value", columnDefinition = "TEXT")
    val configValue: String? = null,
    
    @Column(name = "description", length = 255)
    val description: String? = null,
    
    @Column(name = "created_at", nullable = false)
    val createdAt: Long = System.currentTimeMillis(),
    
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Long = System.currentTimeMillis()
)

