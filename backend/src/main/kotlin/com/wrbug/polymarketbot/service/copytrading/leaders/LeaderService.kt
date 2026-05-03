package com.wrbug.polymarketbot.service.copytrading.leaders

import com.wrbug.polymarketbot.dto.LeaderAddRequest
import com.wrbug.polymarketbot.dto.LeaderBalanceResponse
import com.wrbug.polymarketbot.dto.LeaderDto
import com.wrbug.polymarketbot.dto.LeaderListRequest
import com.wrbug.polymarketbot.dto.LeaderListResponse
import com.wrbug.polymarketbot.dto.LeaderUpdateRequest
import com.wrbug.polymarketbot.dto.WalletBalanceResponse
import com.wrbug.polymarketbot.entity.Leader
import com.wrbug.polymarketbot.repository.AccountRepository
import com.wrbug.polymarketbot.repository.BacktestTaskRepository
import com.wrbug.polymarketbot.repository.CopyTradingRepository
import com.wrbug.polymarketbot.repository.LeaderCopyTradingControlRepository
import com.wrbug.polymarketbot.repository.LeaderRepository
import com.wrbug.polymarketbot.service.common.BlockchainService
import com.wrbug.polymarketbot.util.CategoryValidator
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class LeaderService(
    private val leaderRepository: LeaderRepository,
    private val accountRepository: AccountRepository,
    private val copyTradingRepository: CopyTradingRepository,
    private val leaderCopyTradingControlRepository: LeaderCopyTradingControlRepository,
    private val backtestTaskRepository: BacktestTaskRepository,
    private val blockchainService: BlockchainService
) {

    private val logger = LoggerFactory.getLogger(LeaderService::class.java)

    @Transactional
    fun addLeader(request: LeaderAddRequest): Result<LeaderDto> {
        return try {
            if (!isValidWalletAddress(request.leaderAddress)) {
                return Result.failure(IllegalArgumentException("无效的钱包地址格式"))
            }

            val normalizedCategory = normalizeOptionalText(request.category)
            normalizedCategory?.let(CategoryValidator::validate)

            if (leaderRepository.existsByLeaderAddress(request.leaderAddress)) {
                return Result.failure(IllegalArgumentException("该 Leader 地址已存在"))
            }

            if (accountRepository.existsByWalletAddress(request.leaderAddress)) {
                return Result.failure(IllegalArgumentException("Leader 地址不能与自己的账户地址相同"))
            }

            val website = if (request.website.isNullOrBlank()) {
                "https://polymarket.com/profile/${request.leaderAddress}"
            } else {
                request.website
            }

            val leader = Leader(
                leaderAddress = request.leaderAddress,
                leaderName = normalizeOptionalText(request.leaderName),
                category = normalizedCategory,
                customGroup = normalizeOptionalText(request.customGroup),
                remark = normalizeOptionalText(request.remark),
                website = website
            )

            Result.success(toDto(leaderRepository.save(leader)))
        } catch (e: Exception) {
            logger.error("添加 Leader 失败", e)
            Result.failure(e)
        }
    }

    @Transactional
    fun updateLeader(request: LeaderUpdateRequest): Result<LeaderDto> {
        return try {
            val leader = leaderRepository.findById(request.leaderId).orElse(null)
                ?: return Result.failure(IllegalArgumentException("Leader 不存在"))

            val normalizedCategory = if (request.category == null) {
                leader.category
            } else {
                normalizeOptionalText(request.category)
            }
            normalizedCategory?.let(CategoryValidator::validate)

            val website = if (request.website.isNullOrBlank()) {
                "https://polymarket.com/profile/${leader.leaderAddress}"
            } else {
                request.website
            }

            val updated = leader.copy(
                leaderName = resolveOptionalTextUpdate(leader.leaderName, request.leaderName),
                category = normalizedCategory,
                customGroup = resolveOptionalTextUpdate(leader.customGroup, request.customGroup),
                remark = resolveOptionalTextUpdate(leader.remark, request.remark),
                website = website,
                updatedAt = System.currentTimeMillis()
            )

            Result.success(toDto(leaderRepository.save(updated)))
        } catch (e: Exception) {
            logger.error("更新 Leader 失败", e)
            Result.failure(e)
        }
    }

    @Transactional
    fun deleteLeader(leaderId: Long): Result<Unit> {
        return try {
            val leader = leaderRepository.findById(leaderId).orElse(null)
                ?: return Result.failure(IllegalArgumentException("Leader 不存在"))

            val copyTradings = copyTradingRepository.findByLeaderId(leaderId)
            if (copyTradings.isNotEmpty()) {
                copyTradingRepository.deleteAll(copyTradings)
                copyTradingRepository.flush()
            }

            leaderCopyTradingControlRepository.deleteByLeaderId(leaderId)
            leaderCopyTradingControlRepository.flush()

            val backtestTasks = backtestTaskRepository.findByLeaderId(leaderId)
            if (backtestTasks.isNotEmpty()) {
                backtestTaskRepository.deleteAll(backtestTasks)
                backtestTaskRepository.flush()
            }

            leaderRepository.delete(leader)
            leaderRepository.flush()

            Result.success(Unit)
        } catch (e: Exception) {
            logger.error("删除 Leader 失败", e)
            Result.failure(e)
        }
    }

    fun getLeaderList(request: LeaderListRequest): Result<LeaderListResponse> {
        return try {
            val normalizedCategory = normalizeOptionalText(request.category)
            normalizedCategory?.let(CategoryValidator::validate)

            val leaders = if (normalizedCategory != null) {
                leaderRepository.findByCategory(normalizedCategory)
            } else {
                leaderRepository.findAllByOrderByCreatedAtAsc()
            }

            val leaderDtos = leaders.map { leader ->
                val copyTradingCount = copyTradingRepository.countByLeaderId(leader.id!!)
                val monitoringEnabled = copyTradingRepository.existsByLeaderIdAndEnabledTrue(leader.id)
                val backtestCount = backtestTaskRepository.findByLeaderId(leader.id).size.toLong()
                toDto(leader, copyTradingCount, monitoringEnabled, backtestCount)
            }

            Result.success(
                LeaderListResponse(
                    list = leaderDtos,
                    total = leaderDtos.size.toLong()
                )
            )
        } catch (e: Exception) {
            logger.error("查询 Leader 列表失败", e)
            Result.failure(e)
        }
    }

    fun getLeaderDetail(leaderId: Long): Result<LeaderDto> {
        return try {
            val leader = leaderRepository.findById(leaderId).orElse(null)
                ?: return Result.failure(IllegalArgumentException("Leader 不存在"))

            val copyTradingCount = copyTradingRepository.countByLeaderId(leaderId)
            val monitoringEnabled = copyTradingRepository.existsByLeaderIdAndEnabledTrue(leaderId)
            val backtestCount = backtestTaskRepository.findByLeaderId(leaderId).size.toLong()
            Result.success(toDto(leader, copyTradingCount, monitoringEnabled, backtestCount))
        } catch (e: Exception) {
            logger.error("查询 Leader 详情失败", e)
            Result.failure(e)
        }
    }

    fun getLeaderBalance(leaderId: Long): Result<LeaderBalanceResponse> {
        return try {
            val leader = leaderRepository.findById(leaderId).orElse(null)
                ?: return Result.failure(IllegalArgumentException("Leader 不存在"))

            val balanceResult = runBlocking {
                blockchainService.getWalletBalance(leader.leaderAddress)
            }

            balanceResult.map { walletBalance: WalletBalanceResponse ->
                LeaderBalanceResponse(
                    leaderId = leader.id!!,
                    leaderAddress = leader.leaderAddress,
                    leaderName = leader.leaderName,
                    availableBalance = walletBalance.availableBalance,
                    positionBalance = walletBalance.positionBalance,
                    totalBalance = walletBalance.totalBalance,
                    positions = walletBalance.positions
                )
            }
        } catch (e: Exception) {
            logger.error("查询 Leader 余额失败", e)
            Result.failure(e)
        }
    }

    private fun toDto(
        leader: Leader,
        copyTradingCount: Long = 0,
        monitoringEnabled: Boolean = false,
        backtestCount: Long = 0
    ): LeaderDto {
        return LeaderDto(
            id = leader.id!!,
            leaderAddress = leader.leaderAddress,
            leaderName = leader.leaderName,
            category = leader.category,
            customGroup = leader.customGroup,
            remark = leader.remark,
            website = leader.website,
            copyTradingCount = copyTradingCount,
            monitoringEnabled = monitoringEnabled,
            backtestCount = backtestCount,
            createdAt = leader.createdAt,
            updatedAt = leader.updatedAt
        )
    }

    private fun isValidWalletAddress(address: String): Boolean {
        return address.matches(Regex("^0x[a-fA-F0-9]{40}$"))
    }

    private fun normalizeOptionalText(value: String?): String? {
        return value?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun resolveOptionalTextUpdate(current: String?, requestValue: String?): String? {
        return if (requestValue == null) current else normalizeOptionalText(requestValue)
    }
}
