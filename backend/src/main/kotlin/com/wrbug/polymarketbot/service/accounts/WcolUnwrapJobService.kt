package com.wrbug.polymarketbot.service.accounts

import com.wrbug.polymarketbot.service.system.RelayClientService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service

@Service
class WcolUnwrapJobService(
    private val accountService: AccountService,
    private val relayClientService: RelayClientService
) {
    private val logger = LoggerFactory.getLogger(WcolUnwrapJobService::class.java)
    private val scope = kotlinx.coroutines.CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var unwrapJob: Job? = null

    @Scheduled(fixedRate = 20_000)
    fun runWcolUnwrapPolling() {
        if (unwrapJob?.isActive == true) {
            logger.debug("上一轮 WCOL 解包任务仍在执行，跳过本次")
            return
        }
        unwrapJob = scope.launch {
            try {
                accountService.runWcolUnwrapForAllAccounts()
            } catch (e: Exception) {
                logger.error("WCOL unwrap polling failed: ${e.message}", e)
            } finally {
                unwrapJob = null
            }
        }
    }
}

