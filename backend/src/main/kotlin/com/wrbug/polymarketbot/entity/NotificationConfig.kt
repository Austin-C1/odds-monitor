package com.wrbug.polymarketbot.entity

import jakarta.persistence.*

@Entity
@Table(name = "notification_configs")
data class NotificationConfig(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,
    
    @Column(name = "type", nullable = false, length = 50)
    val type: String,
    
    @Column(name = "name", nullable = false, length = 100)
    val name: String,
    
    @Column(name = "enabled", nullable = false)
    var enabled: Boolean = true,
    
    @Column(name = "config_json", nullable = false, columnDefinition = "TEXT")
    val configJson: String,
    
    @Column(name = "created_at", nullable = false)
    val createdAt: Long = System.currentTimeMillis(),
    
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Long = System.currentTimeMillis()
)

