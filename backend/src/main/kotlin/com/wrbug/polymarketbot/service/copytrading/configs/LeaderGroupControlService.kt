package com.wrbug.polymarketbot.service.copytrading.configs

import com.wrbug.polymarketbot.dto.LeaderGroupControlDto
import com.wrbug.polymarketbot.dto.LeaderGroupControlListResponse
import com.wrbug.polymarketbot.dto.LeaderGroupControlUpdateRequest
import com.wrbug.polymarketbot.entity.CopyTrading
import com.wrbug.polymarketbot.entity.Leader
import com.wrbug.polymarketbot.entity.LeaderCopyTradingControl
import com.wrbug.polymarketbot.repository.CopyOrderTrackingRepository
import com.wrbug.polymarketbot.repository.CopyTradingRepository
import com.wrbug.polymarketbot.repository.LeaderCopyTradingControlRepository
import com.wrbug.polymarketbot.repository.LeaderRepository
import com.wrbug.polymarketbot.repository.SellMatchDetailRepository
import com.wrbug.polymarketbot.service.accounts.AccountService
import com.wrbug.polymarketbot.service.copytrading.monitor.CopyTradingMonitoringRefreshEvent
import com.wrbug.polymarketbot.service.copytrading.statistics.CopyTradingCurrentMetrics
import com.wrbug.polymarketbot.service.copytrading.statistics.LeaderCurvePoint
import com.wrbug.polymarketbot.service.copytrading.statistics.LeaderDrawdownEvaluator
import com.wrbug.polymarketbot.service.copytrading.statistics.resolveCopyTradingCurrentMetrics
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode

@Service
class LeaderGroupControlService(
    private val copyTradingRepository: CopyTradingRepository,
    private val copyOrderTrackingRepository: CopyOrderTrackingRepository,
    private val leaderRepository: LeaderRepository,
    private val leaderCopyTradingControlRepository: LeaderCopyTradingControlRepository,
    private val sellMatchDetailRepository: SellMatchDetailRepository,
    private val leaderDrawdownEvaluator: LeaderDrawdownEvaluator,
    private val accountService: AccountService,
    private val applicationEventPublisher: ApplicationEventPublisher
) {

    private val logger = LoggerFactory.getLogger(LeaderGroupControlService::class.java)

    fun listControls(leaderIds: List<Long>? = null): Result<LeaderGroupControlListResponse> {
        return try {
            val targetLeaderIds = leaderIds
                ?.distinct()
                ?.filter { it > 0 }
                ?: copyTradingRepository.findAll().map { it.leaderId }.distinct()

            if (targetLeaderIds.isEmpty()) {
                return Result.success(LeaderGroupControlListResponse(emptyList()))
            }

            val leaders = leaderRepository.findAllById(targetLeaderIds).associateBy { it.id!! }
            val controls = leaderCopyTradingControlRepository.findByLeaderIdIn(targetLeaderIds).associateBy { it.leaderId }
            val copyTradingsByLeaderId = copyTradingRepository.findByLeaderIdIn(targetLeaderIds).groupBy { it.leaderId }
            val currentMetricsByCopyTradingId = loadCurrentMetrics(copyTradingsByLeaderId.values.flatten())
            val now = System.currentTimeMillis()

            val list = targetLeaderIds.mapNotNull { leaderId ->
                val leader = leaders[leaderId] ?: return@mapNotNull null
                val baseControl = controls[leaderId] ?: createDefaultControl(leaderId)
                val liveControl = applyLiveMetrics(
                    control = baseControl,
                    copyTradings = copyTradingsByLeaderId[leaderId].orEmpty(),
                    now = now,
                    currentMetricsByCopyTradingId = currentMetricsByCopyTradingId
                )
                toDto(liveControl, leader)
            }

            Result.success(LeaderGroupControlListResponse(list))
        } catch (e: Exception) {
            logger.error("Failed to list leader group controls", e)
            Result.failure(e)
        }
    }

    @Transactional
    fun closeGroup(leaderId: Long): Result<LeaderGroupControlDto> {
        return try {
            val leader = leaderRepository.findById(leaderId).orElse(null)
                ?: return Result.failure(IllegalArgumentException("Leader not found"))
            val copyTradings = copyTradingRepository.findByLeaderId(leaderId)
            if (copyTradings.isEmpty()) {
                return Result.failure(IllegalArgumentException("No copy trading configs under this leader"))
            }

            val now = System.currentTimeMillis()
            val base = loadOrCreateControlForUpdate(leaderId)
            copyTradingRepository.saveAll(copyTradings.map { it.copy(enabled = false, updatedAt = now) })
            publishMonitoringRefresh(copyTradings, leaderId)

            val saved = leaderCopyTradingControlRepository.save(
                base.copy(
                    autoPauseEnabled = true,
                    status = STATUS_MANUAL_PAUSED,
                    pausedReason = "Manually paused",
                    updatedAt = now
                )
            )

            Result.success(toDto(saved, leader))
        } catch (e: Exception) {
            logger.error("Failed to close leader group", e)
            Result.failure(e)
        }
    }

    @Transactional
    fun restartGroup(leaderId: Long): Result<LeaderGroupControlDto> {
        return try {
            val leader = leaderRepository.findById(leaderId).orElse(null)
                ?: return Result.failure(IllegalArgumentException("Leader not found"))
            val copyTradings = copyTradingRepository.findByLeaderId(leaderId)
            if (copyTradings.isEmpty()) {
                return Result.failure(IllegalArgumentException("No copy trading configs under this leader"))
            }

            val now = System.currentTimeMillis()
            val base = loadOrCreateControlForUpdate(leaderId)
            copyTradingRepository.saveAll(copyTradings.map { it.copy(enabled = true, updatedAt = now) })
            publishMonitoringRefresh(copyTradings, leaderId)

            val saved = leaderCopyTradingControlRepository.save(
                base.copy(
                    autoPauseEnabled = true,
                    status = STATUS_ACTIVE,
                    pausedReason = null,
                    autoPausedAt = null,
                    updatedAt = now
                )
            )

            Result.success(toDto(saved, leader))
        } catch (e: Exception) {
            logger.error("Failed to restart leader group", e)
            Result.failure(e)
        }
    }

    @Transactional
    fun updateControl(request: LeaderGroupControlUpdateRequest): Result<LeaderGroupControlDto> {
        return try {
            val leader = leaderRepository.findById(request.leaderId).orElse(null)
                ?: return Result.failure(IllegalArgumentException("Leader not found"))

            val profitTakePrice = request.profitTakePrice.toBigDecimalOrNull()
                ?: return Result.failure(IllegalArgumentException("Profit take price is invalid"))
            if (profitTakePrice <= BigDecimal.ZERO || profitTakePrice > BigDecimal.ONE) {
                return Result.failure(IllegalArgumentException("Profit take price must be between 0 and 1"))
            }
            val drawdownThresholdPercent = request.drawdownThresholdPercent.toBigDecimalOrNull()
                ?.setScale(2, RoundingMode.HALF_UP)
                ?: return Result.failure(IllegalArgumentException("Drawdown threshold percent is invalid"))
            if (drawdownThresholdPercent <= BigDecimal.ZERO || drawdownThresholdPercent > BigDecimal("100")) {
                return Result.failure(IllegalArgumentException("Drawdown threshold percent must be between 0 and 100"))
            }

            val now = System.currentTimeMillis()
            val base = loadOrCreateControlForUpdate(request.leaderId)
            val saved = leaderCopyTradingControlRepository.save(
                base.copy(
                    autoPauseEnabled = request.autoPauseEnabled,
                    profitTakeEnabled = request.profitTakeEnabled,
                    profitTakePrice = profitTakePrice,
                    drawdownThresholdPercent = drawdownThresholdPercent,
                    updatedAt = now
                )
            )

            Result.success(toDto(saved, leader))
        } catch (e: Exception) {
            logger.error("Failed to update leader group control", e)
            Result.failure(e)
        }
    }

    @Transactional
    fun evaluateAutoPause(leaderId: Long): Result<LeaderGroupControlDto> {
        return try {
            val leader = leaderRepository.findById(leaderId).orElse(null)
                ?: return Result.failure(IllegalArgumentException("Leader not found"))
            val copyTradings = copyTradingRepository.findByLeaderId(leaderId)
            if (copyTradings.isEmpty()) {
                return Result.success(toDto(createDefaultControl(leaderId), leader))
            }

            val now = System.currentTimeMillis()
            val control = loadOrCreateControlForUpdate(leaderId)
            val currentMetricsByCopyTradingId = loadCurrentMetrics(copyTradings)
            var next = applyLiveMetrics(
                control = control,
                copyTradings = copyTradings,
                now = now,
                currentMetricsByCopyTradingId = currentMetricsByCopyTradingId
            )

            if (control.status == STATUS_ACTIVE && control.autoPauseEnabled && next.currentDrawdownPercent >= control.drawdownThresholdPercent) {
                copyTradingRepository.saveAll(copyTradings.map { it.copy(enabled = false, updatedAt = now) })
                publishMonitoringRefresh(copyTradings, leaderId)
                next = next.copy(
                    status = STATUS_AUTO_PAUSED,
                    pausedReason = "Paused after ${control.drawdownThresholdPercent.toPlainString()}% drawdown from the 7-day peak",
                    autoPausedAt = now,
                    updatedAt = now
                )
            }

            val saved = leaderCopyTradingControlRepository.save(next)
            Result.success(toDto(saved, leader))
        } catch (e: Exception) {
            logger.error("Failed to evaluate leader group drawdown", e)
            Result.failure(e)
        }
    }

    private fun loadOrCreateControlForUpdate(leaderId: Long): LeaderCopyTradingControl {
        leaderCopyTradingControlRepository.findByLeaderIdForUpdate(leaderId)?.let { return it }

        try {
            leaderCopyTradingControlRepository.save(createDefaultControl(leaderId))
        } catch (e: DataIntegrityViolationException) {
            logger.debug("Leader control was created concurrently: leaderId=$leaderId")
        }

        return leaderCopyTradingControlRepository.findByLeaderIdForUpdate(leaderId)
            ?: throw IllegalStateException("Leader control not found after initialization: leaderId=$leaderId")
    }

    private fun applyLiveMetrics(
        control: LeaderCopyTradingControl,
        copyTradings: List<CopyTrading>,
        now: Long,
        currentMetricsByCopyTradingId: Map<Long, CopyTradingCurrentMetrics>
    ): LeaderCopyTradingControl {
        val evaluation = leaderDrawdownEvaluator.evaluate(
            buildCurvePoints(copyTradings, now, currentMetricsByCopyTradingId),
            control.drawdownThresholdPercent
        )

        return control.copy(
            lastPeakPnl = evaluation.peakPnl,
            currentPnl = evaluation.currentPnl,
            currentDrawdownPercent = evaluation.drawdownPercent,
            lastEvaluatedAt = now,
            updatedAt = now
        )
    }

    private fun buildCurvePoints(
        copyTradings: List<CopyTrading>,
        now: Long,
        currentMetricsByCopyTradingId: Map<Long, CopyTradingCurrentMetrics>
    ): List<LeaderCurvePoint> {
        val since = now - WINDOW_DAYS * DAY_MILLIS
        val copyTradingIds = copyTradings.mapNotNull { it.id }
        if (copyTradingIds.isEmpty()) {
            return emptyList()
        }
        val details = sellMatchDetailRepository
            .findByCopyTradingIdInAndCreatedAtGreaterThanEqual(copyTradingIds, since)
            .sortedBy { it.createdAt }

        var cumulativePnl = BigDecimal.ZERO
        val curvePoints = details.map { detail ->
            cumulativePnl = cumulativePnl.add(detail.realizedPnl)
            LeaderCurvePoint(detail.createdAt, cumulativePnl)
        }

        val currentUnrealizedPnl = copyTradingIds.fold(BigDecimal.ZERO) { sum, copyTradingId ->
            sum.add(currentMetricsByCopyTradingId[copyTradingId]?.unrealizedPnl ?: BigDecimal.ZERO)
        }
        val currentPoint = LeaderCurvePoint(now, cumulativePnl.add(currentUnrealizedPnl))

        return if (curvePoints.isEmpty()) {
            listOf(currentPoint)
        } else {
            curvePoints + currentPoint
        }
    }

    private fun loadCurrentMetrics(copyTradings: List<CopyTrading>): Map<Long, CopyTradingCurrentMetrics> {
        if (copyTradings.isEmpty()) {
            return emptyMap()
        }

        val activeOrdersByCopyTradingId = copyOrderTrackingRepository
            .findActiveOrdersByCopyTradingIdIn(copyTradings.mapNotNull { it.id })
            .groupBy { it.copyTradingId }
        if (activeOrdersByCopyTradingId.isEmpty()) {
            return emptyMap()
        }

        val currentPositionsByAccount = runBlocking {
            copyTradings.map { it.accountId }.distinct().map { accountId ->
                async {
                    accountId to accountService.getCurrentPositionsForAccount(accountId).getOrNull().orEmpty()
                }
            }.awaitAll().toMap()
        }

        return resolveCopyTradingCurrentMetrics(
            copyTradings = copyTradings,
            activeOrdersByCopyTradingId = activeOrdersByCopyTradingId,
            currentPositionsByAccount = currentPositionsByAccount
        )
    }

    private fun createDefaultControl(leaderId: Long): LeaderCopyTradingControl {
        return LeaderCopyTradingControl(
            leaderId = leaderId,
            profitTakeEnabled = true,
            profitTakePrice = DEFAULT_PROFIT_TAKE_PRICE,
            drawdownThresholdPercent = DEFAULT_DRAW_DOWN_THRESHOLD_PERCENT
        )
    }

    private fun toDto(control: LeaderCopyTradingControl, leader: Leader): LeaderGroupControlDto {
        return LeaderGroupControlDto(
            leaderId = leader.id!!,
            leaderName = leader.leaderName,
            leaderAddress = leader.leaderAddress,
            autoPauseEnabled = control.autoPauseEnabled,
            profitTakeEnabled = control.profitTakeEnabled,
            profitTakePrice = control.profitTakePrice.toPlainString(),
            status = control.status,
            pausedReason = control.pausedReason,
            lastPeakPnl = control.lastPeakPnl.toPlainString(),
            currentPnl = control.currentPnl.toPlainString(),
            currentDrawdownPercent = control.currentDrawdownPercent.toPlainString(),
            drawdownThresholdPercent = control.drawdownThresholdPercent.toPlainString(),
            autoPausedAt = control.autoPausedAt,
            lastEvaluatedAt = control.lastEvaluatedAt,
            trackedWindowDays = WINDOW_DAYS.toInt()
        )
    }

    private fun publishMonitoringRefresh(copyTradings: List<CopyTrading>, leaderId: Long) {
        applicationEventPublisher.publishEvent(
            CopyTradingMonitoringRefreshEvent(
                leaderId = leaderId,
                accountIds = copyTradings.map { it.accountId }.distinct()
            )
        )
    }

    companion object {
        private const val DAY_MILLIS = 24 * 60 * 60 * 1000L
        private const val WINDOW_DAYS = 7L
        private val DEFAULT_DRAW_DOWN_THRESHOLD_PERCENT = BigDecimal("25.00")
        val DEFAULT_PROFIT_TAKE_PRICE: BigDecimal = BigDecimal("0.99")
        private const val STATUS_ACTIVE = "ACTIVE"
        private const val STATUS_AUTO_PAUSED = "AUTO_PAUSED"
        private const val STATUS_MANUAL_PAUSED = "MANUAL_PAUSED"
    }
}
