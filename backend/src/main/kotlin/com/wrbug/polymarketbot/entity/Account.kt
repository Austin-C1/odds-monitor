package com.wrbug.polymarketbot.entity

import jakarta.persistence.*

@Entity
@Table(name = "wallet_accounts")
data class Account(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "private_key", nullable = false, length = 500)
    val privateKey: String,

    @Column(name = "wallet_address", nullable = false, length = 42)
    val walletAddress: String,

    @Column(name = "proxy_address", unique = true, nullable = false, length = 42)
    val proxyAddress: String,

    @Column(name = "api_key", length = 500)
    val apiKey: String? = null,

    @Column(name = "api_secret", length = 500)
    val apiSecret: String? = null,

    @Column(name = "api_passphrase", length = 500)
    val apiPassphrase: String? = null,

    @Column(name = "builder_api_key", length = 500)
    val builderApiKey: String? = null,

    @Column(name = "builder_secret", length = 500)
    val builderSecret: String? = null,

    @Column(name = "builder_passphrase", length = 500)
    val builderPassphrase: String? = null,

    @Column(name = "account_name", length = 100)
    val accountName: String? = null,

    @Column(name = "remark", columnDefinition = "TEXT")
    val remark: String? = null,

    @Column(name = "is_default", nullable = false)
    val isDefault: Boolean = false,

    @Column(name = "is_enabled", nullable = false)
    val isEnabled: Boolean = true,

    @Column(name = "auto_redeem_enabled", nullable = false)
    val autoRedeemEnabled: Boolean = true,

    @Column(name = "wallet_type", nullable = false, length = 20)
    val walletType: String = "magic",

    @Column(name = "created_at", nullable = false)
    val createdAt: Long = System.currentTimeMillis(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Long = System.currentTimeMillis()
)
