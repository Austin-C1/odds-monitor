package com.wrbug.polymarketbot.entity

import jakarta.persistence.*

@Entity
@Table(name = "users")
data class User(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,
    
    @Column(name = "username", unique = true, nullable = false, length = 50)
    val username: String,
    
    @Column(name = "password", nullable = false, length = 255)
    val password: String,
    
    @Column(name = "is_default", nullable = false)
    val isDefault: Boolean = false,
    
    @Column(name = "token_version", nullable = false)
    var tokenVersion: Long = 0,
    
    @Column(name = "created_at", nullable = false)
    val createdAt: Long = System.currentTimeMillis(),
    
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Long = System.currentTimeMillis()
)

