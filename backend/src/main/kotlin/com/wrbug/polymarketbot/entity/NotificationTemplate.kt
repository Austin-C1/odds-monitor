package com.wrbug.polymarketbot.entity

import jakarta.persistence.*

@Entity
@Table(name = "notification_templates")
data class NotificationTemplate(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "template_type", unique = true, nullable = false, length = 50)
    val templateType: String,  // ORDER_SUCCESS, ORDER_FAILED, ORDER_FILTERED, CRYPTO_TAIL_SUCCESS, REDEEM_SUCCESS, REDEEM_NO_RETURN

    @Column(name = "template_content", nullable = false, columnDefinition = "TEXT")
    var templateContent: String,

    @Column(name = "is_default", nullable = false)
    var isDefault: Boolean = false,

    @Column(name = "created_at", nullable = false)
    val createdAt: Long = System.currentTimeMillis(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Long = System.currentTimeMillis()
)
