package com.wrbug.polymarketbot.entity

import jakarta.persistence.*

@Entity
@Table(name = "markets", indexes = [
    Index(name = "idx_market_id", columnList = "market_id", unique = true)
])
data class Market(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,
    
    @Column(name = "market_id", unique = true, nullable = false, length = 100)
    val marketId: String,
    
    @Column(name = "title", nullable = false, length = 500)
    val title: String,
    
    @Column(name = "slug", length = 200)
    val slug: String? = null,
    
    @Column(name = "event_slug", length = 200)
    val eventSlug: String? = null,
    
    @Column(name = "category", length = 50)
    val category: String? = null,
    
    @Column(name = "icon", length = 500)
    val icon: String? = null,
    
    @Column(name = "image", length = 500)
    val image: String? = null,
    
    @Column(name = "description", columnDefinition = "TEXT")
    val description: String? = null,
    
    @Column(name = "active", nullable = false)
    val active: Boolean = true,
    
    @Column(name = "closed", nullable = false)
    val closed: Boolean = false,
    
    @Column(name = "archived", nullable = false)
    val archived: Boolean = false,
    
    @Column(name = "end_date")
    val endDate: Long? = null,
    
    @Column(name = "created_at", nullable = false)
    val createdAt: Long = System.currentTimeMillis(),
    
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Long = System.currentTimeMillis()
)

