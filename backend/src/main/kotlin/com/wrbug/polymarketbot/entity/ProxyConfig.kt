package com.wrbug.polymarketbot.entity

import jakarta.persistence.*

@Entity
@Table(name = "proxy_config")
data class ProxyConfig(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,
    
    @Column(name = "type", nullable = false, length = 20)
    val type: String,  // HTTP, CLASH, SS
    
    @Column(name = "enabled", nullable = false)
    val enabled: Boolean = false,
    
    @Column(name = "host", length = 255)
    val host: String? = null,
    
    @Column(name = "port")
    val port: Int? = null,
    
    @Column(name = "username", length = 100)
    val username: String? = null,
    
    @Column(name = "password", length = 255)
    val password: String? = null,
    
    @Column(name = "subscription_url", length = 500)
    val subscriptionUrl: String? = null,
    
    @Column(name = "subscription_config", columnDefinition = "TEXT")
    val subscriptionConfig: String? = null,
    
    @Column(name = "last_subscription_update")
    val lastSubscriptionUpdate: Long? = null,
    
    @Column(name = "created_at", nullable = false)
    val createdAt: Long = System.currentTimeMillis(),
    
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Long = System.currentTimeMillis()
)

