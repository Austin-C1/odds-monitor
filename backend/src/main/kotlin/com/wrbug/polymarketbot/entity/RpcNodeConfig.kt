package com.wrbug.polymarketbot.entity

import jakarta.persistence.*

@Entity
@Table(name = "rpc_node_config")
data class RpcNodeConfig(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,
    
    @Column(name = "provider_type", nullable = false, length = 50)
    val providerType: String,
    
    @Column(name = "name", nullable = false, length = 100)
    val name: String,
    
    @Column(name = "http_url", nullable = false, length = 500)
    val httpUrl: String,  // HTTP RPC URL
    
    @Column(name = "ws_url", length = 500)
    val wsUrl: String? = null,
    
    @Column(name = "api_key", length = 200)
    val apiKey: String? = null,
    
    @Column(name = "enabled", nullable = false)
    var enabled: Boolean = true,
    
    @Column(name = "priority", nullable = false)
    var priority: Int = 0,
    
    @Column(name = "last_check_time")
    var lastCheckTime: Long? = null,
    
    @Column(name = "last_check_status", length = 20)
    var lastCheckStatus: String? = null,
    
    @Column(name = "response_time_ms")
    var responseTimeMs: Int? = null,
    
    @Column(name = "created_at", nullable = false)
    val createdAt: Long = System.currentTimeMillis(),
    
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Long = System.currentTimeMillis()
)

enum class NodeHealthStatus {
    HEALTHY,
    UNHEALTHY,
    UNKNOWN
}

enum class RpcProviderType {
    ALCHEMY,
    INFURA,
    QUICKNODE,
    CHAINSTACK,
    GETBLOCK,
    CUSTOM,
    PUBLIC
}
