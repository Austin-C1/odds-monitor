package com.wrbug.polymarketbot.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.math.BigDecimal

@Entity
@Table(name = "auto_betting_intents")
data class AutoBettingIntent(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,
    @Column(name = "dedupe_key", nullable = false, length = 255)
    val dedupeKey: String = "",
    @Column(name = "active_dedupe_key", length = 255)
    val activeDedupeKey: String? = null,
    @Column(name = "signal_source", nullable = false, length = 64)
    val signalSource: String = "",
    @Column(name = "betting_mode", nullable = false, length = 16)
    val bettingMode: String = "",
    @Column(name = "match_phase", nullable = false, length = 16)
    val matchPhase: String = "",
    @Column(name = "account_key", nullable = false, length = 64)
    val accountKey: String = "",
    @Column(name = "league_name", nullable = false, length = 128)
    val leagueName: String = "",
    @Column(name = "match_title", nullable = false, length = 255)
    val matchTitle: String = "",
    @Column(name = "market_type", nullable = false, length = 32)
    val marketType: String = "",
    @Column(name = "line_value", length = 32)
    val lineValue: String? = null,
    @Column(name = "selection_name", nullable = false, length = 64)
    val selectionName: String = "",
    @Column(name = "reference_source_key", nullable = false, length = 32)
    val referenceSourceKey: String = "",
    @Column(name = "target_source_key", nullable = false, length = 32)
    val targetSourceKey: String = "",
    @Column(name = "reference_odds", nullable = false, precision = 18, scale = 8)
    val referenceOdds: BigDecimal = BigDecimal.ZERO,
    @Column(name = "target_odds", nullable = false, precision = 18, scale = 8)
    val targetOdds: BigDecimal = BigDecimal.ZERO,
    @Column(name = "target_decimal_odds", nullable = false, precision = 18, scale = 8)
    val targetDecimalOdds: BigDecimal = BigDecimal.ZERO,
    @Column(name = "decimal_edge", nullable = false, precision = 18, scale = 8)
    val decimalEdge: BigDecimal = BigDecimal.ZERO,
    @Column(name = "stake_amount", nullable = false, precision = 18, scale = 4)
    val stakeAmount: BigDecimal = BigDecimal.ZERO,
    @Column(name = "status", nullable = false, length = 32)
    val status: String = "",
    @Column(name = "reject_reason", length = 64)
    val rejectReason: String? = null,
    @Column(name = "crown_history_verified", nullable = false)
    val crownHistoryVerified: Boolean = false,
    @Column(name = "crown_history_checked_at")
    val crownHistoryCheckedAt: Long? = null,
    @Column(name = "crown_bet_reference", length = 128)
    val crownBetReference: String? = null,
    @Column(name = "captured_at", nullable = false)
    val capturedAt: Long = 0,
    @Column(name = "created_at", nullable = false)
    val createdAt: Long = System.currentTimeMillis(),
    @Column(name = "updated_at", nullable = false)
    val updatedAt: Long = System.currentTimeMillis()
)
