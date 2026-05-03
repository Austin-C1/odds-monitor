package com.wrbug.polymarketbot.service.accounts

import com.wrbug.polymarketbot.entity.SellMatchDetail
import com.wrbug.polymarketbot.entity.SellMatchRecord
import com.wrbug.polymarketbot.repository.CopyOrderTrackingRepository
import com.wrbug.polymarketbot.repository.SellMatchDetailRepository
import com.wrbug.polymarketbot.repository.SellMatchRecordRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal

data class OrderSettlementMatch(
    val trackingId: Long,
    val buyOrderId: String,
    val matchedQuantity: BigDecimal,
    val buyPrice: BigDecimal,
    val sellPrice: BigDecimal,
    val realizedPnl: BigDecimal,
    val newMatchedQuantity: BigDecimal,
    val newRemainingQuantity: BigDecimal,
    val newStatus: String,
    val updatedAt: Long
)

@Service
class PositionSettlementWriter(
    private val copyOrderTrackingRepository: CopyOrderTrackingRepository,
    private val sellMatchRecordRepository: SellMatchRecordRepository,
    private val sellMatchDetailRepository: SellMatchDetailRepository
) {

    @Transactional
    fun persistSettlement(
        copyTradingId: Long,
        marketId: String,
        outcomeIndex: Int,
        sellPrice: BigDecimal,
        orderMatches: List<OrderSettlementMatch>,
        sellOrderPrefix: String,
        leaderTradePrefix: String
    ) {
        if (orderMatches.isEmpty()) {
            return
        }

        val ordersById = copyOrderTrackingRepository.findAllById(orderMatches.map { it.trackingId })
            .associateBy { it.id!! }
        val updatedOrders = orderMatches.map { match ->
            val order = ordersById[match.trackingId]
                ?: throw IllegalStateException("订单不存在: ${match.trackingId}")
            order.matchedQuantity = match.newMatchedQuantity
            order.remainingQuantity = match.newRemainingQuantity
            order.status = match.newStatus
            order.updatedAt = match.updatedAt
            order
        }
        copyOrderTrackingRepository.saveAll(updatedOrders)

        val totalMatchedQuantity = orderMatches.fold(BigDecimal.ZERO) { total, match ->
            total.add(match.matchedQuantity)
        }
        if (totalMatchedQuantity <= BigDecimal.ZERO) {
            return
        }

        val totalRealizedPnl = orderMatches.fold(BigDecimal.ZERO) { total, match ->
            total.add(match.realizedPnl)
        }
        val timestamp = System.currentTimeMillis()
        val matchRecord = sellMatchRecordRepository.save(
            SellMatchRecord(
                copyTradingId = copyTradingId,
                sellOrderId = "${sellOrderPrefix}_${timestamp}_${copyTradingId}",
                leaderSellTradeId = "${leaderTradePrefix}_${timestamp}",
                marketId = marketId,
                side = outcomeIndex.toString(),
                outcomeIndex = outcomeIndex,
                totalMatchedQuantity = totalMatchedQuantity,
                sellPrice = sellPrice,
                totalRealizedPnl = totalRealizedPnl,
                priceUpdated = true
            )
        )

        sellMatchDetailRepository.saveAll(
            orderMatches.map { match ->
                SellMatchDetail(
                    matchRecordId = matchRecord.id!!,
                    trackingId = match.trackingId,
                    buyOrderId = match.buyOrderId,
                    matchedQuantity = match.matchedQuantity,
                    buyPrice = match.buyPrice,
                    sellPrice = match.sellPrice,
                    realizedPnl = match.realizedPnl
                )
            }
        )
    }
}
