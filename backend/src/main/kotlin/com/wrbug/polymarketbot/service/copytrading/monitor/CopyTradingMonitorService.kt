package com.wrbug.polymarketbot.service.copytrading.monitor

import com.wrbug.polymarketbot.entity.CopyTrading
import com.wrbug.polymarketbot.entity.Leader
import com.wrbug.polymarketbot.repository.AccountRepository
import com.wrbug.polymarketbot.repository.CopyTradingRepository
import com.wrbug.polymarketbot.repository.LeaderRepository
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener

@Service
class CopyTradingMonitorService(
    private val copyTradingRepository: CopyTradingRepository,
    private val leaderRepository: LeaderRepository,
    private val accountRepository: AccountRepository,
    private val activityWsService: PolymarketActivityWsService,
    private val onChainWsService: OnChainWsService,
    private val accountOnChainMonitorService: AccountOnChainMonitorService
) {
    
    private val logger = LoggerFactory.getLogger(CopyTradingMonitorService::class.java)
    
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private data class MonitoringTargets(
        val leaders: List<Leader>,
        val accounts: List<com.wrbug.polymarketbot.entity.Account>
    )
    
    @PostConstruct
    fun init() {
        scope.launch {
            try {
                startMonitoring()
            } catch (e: Exception) {
                logger.error("启动跟单监听失败", e)
            }
        }
    }
    
    @PreDestroy
    fun destroy() {
        scope.cancel()
        activityWsService.stop()
        onChainWsService.stop()
        accountOnChainMonitorService.stop()
    }
    
    suspend fun startMonitoring() {
        val enabledCopyTradings = copyTradingRepository.findByEnabledTrue()
        
        if (enabledCopyTradings.isEmpty()) {
            activityWsService.stop()
            onChainWsService.stop()
            accountOnChainMonitorService.stop()
            return
        }

        val targets = resolveMonitoringTargets(enabledCopyTradings)
        if (targets.leaders.isEmpty() || targets.accounts.isEmpty()) {
            logger.warn("No valid copy-trading leader/account pairs remain, stopping monitoring")
            activityWsService.stop()
            onChainWsService.stop()
            accountOnChainMonitorService.stop()
            return
        }
        activityWsService.start(targets.leaders)
        onChainWsService.start(targets.leaders)
        accountOnChainMonitorService.start(targets.accounts)
    }
    
    suspend fun addLeaderMonitoring(leaderId: Long) {
        val copyTradings = copyTradingRepository.findByLeaderIdAndEnabledTrue(leaderId)
        val targets = resolveMonitoringTargets(copyTradings)
        val leader = targets.leaders.firstOrNull { it.id == leaderId }
            ?: run {
                removeLeaderMonitoring(leaderId)
                return
            }

        if (targets.accounts.isEmpty()) {
            return
        }
        activityWsService.addLeader(leader)
        onChainWsService.addLeader(leader)
    }
    
    suspend fun removeLeaderMonitoring(leaderId: Long) {
        val copyTradings = copyTradingRepository.findByLeaderIdAndEnabledTrue(leaderId)
        if (resolveMonitoringTargets(copyTradings).leaders.any { it.id == leaderId }) {
            return
        }
        activityWsService.removeLeader(leaderId)
        onChainWsService.removeLeader(leaderId)
    }
    
    suspend fun updateLeaderMonitoring(leaderId: Long) {
        val copyTradings = copyTradingRepository.findByLeaderIdAndEnabledTrue(leaderId)
        val targets = resolveMonitoringTargets(copyTradings)
        val leader = targets.leaders.firstOrNull { it.id == leaderId }
        
        if (leader != null && targets.accounts.isNotEmpty()) {
            activityWsService.addLeader(leader)
            onChainWsService.addLeader(leader)
            targets.accounts.forEach { account ->
                val accountId = account.id ?: return@forEach
                accountOnChainMonitorService.addAccount(account)
                updateAccountMonitoring(accountId)
            }
        } else {
            activityWsService.removeLeader(leaderId)
            onChainWsService.removeLeader(leaderId)
        }
    }
    
    suspend fun updateAccountMonitoring(accountId: Long) {
        val copyTradings = copyTradingRepository.findByAccountId(accountId)
            .filter { it.enabled }
        val targets = resolveMonitoringTargets(copyTradings)
        val account = targets.accounts.firstOrNull { it.id == accountId }
        
        if (account != null && targets.leaders.isNotEmpty()) {
            accountOnChainMonitorService.addAccount(account)
        } else {
            accountOnChainMonitorService.removeAccount(accountId)
        }
    }

    private fun resolveMonitoringTargets(copyTradings: List<CopyTrading>): MonitoringTargets {
        if (copyTradings.isEmpty()) {
            return MonitoringTargets(emptyList(), emptyList())
        }

        val leadersById = leaderRepository.findAllById(copyTradings.map { it.leaderId }.distinct())
            .associateBy { it.id!! }
        val accountsById = accountRepository.findAllById(copyTradings.map { it.accountId }.distinct())
            .associateBy { it.id!! }

        val validCopyTradings = copyTradings.filter { copyTrading ->
            leadersById.containsKey(copyTrading.leaderId) && accountsById.containsKey(copyTrading.accountId)
        }

        return MonitoringTargets(
            leaders = validCopyTradings.mapNotNull { leadersById[it.leaderId] }.distinctBy { it.id },
            accounts = validCopyTradings.mapNotNull { accountsById[it.accountId] }.distinctBy { it.id }
        )
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun handleMonitoringRefreshEvent(event: CopyTradingMonitoringRefreshEvent) {
        scope.launch {
            try {
                updateLeaderMonitoring(event.leaderId)
                event.accountIds.distinct().forEach { accountId ->
                    updateAccountMonitoring(accountId)
                }
            } catch (e: Exception) {
                logger.error("同步跟单监控失败", e)
            }
        }
    }
    
    suspend fun restartMonitoring() {
        activityWsService.stop()
        onChainWsService.stop()
        delay(1000)
        startMonitoring()
    }
}

