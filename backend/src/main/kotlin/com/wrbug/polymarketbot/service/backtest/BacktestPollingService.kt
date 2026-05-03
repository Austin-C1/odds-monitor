package com.wrbug.polymarketbot.service.backtest

import com.wrbug.polymarketbot.entity.BacktestTask
import com.wrbug.polymarketbot.repository.BacktestTaskRepository
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadPoolExecutor
import kotlinx.coroutines.runBlocking

@Service
class BacktestPollingService(
    private val backtestTaskRepository: BacktestTaskRepository,
    private val executionService: BacktestExecutionService
) {
    private val logger = LoggerFactory.getLogger(BacktestPollingService::class.java)
    private val executor: ExecutorService = Executors.newFixedThreadPool(1) as ThreadPoolExecutor

    @Scheduled(fixedDelay = 10000)
    fun pollPendingTasks() {
        try {
            val runningTasks = backtestTaskRepository.findByStatus("RUNNING")
            if (runningTasks.isNotEmpty()) {
                val activeQueueSize = (executor as ThreadPoolExecutor).queue.size
                val activeCount = (executor as ThreadPoolExecutor).activeCount
                if (activeCount == 0 && runningTasks.isNotEmpty()) {
                    logger.info("检测到应用重启导致的异常 RUNNING 任务，重置为 PENDING 以便恢复")
                    runningTasks.forEach { task ->
                        val now = System.currentTimeMillis()
                        val executionStartedAt = task.executionStartedAt
                        val executionDuration = if (executionStartedAt != null) {
                            now - executionStartedAt
                        } else {
                            0L
                        }
                        if (executionDuration > 60000) {
                            logger.info("重置异常 RUNNING 任务: taskId=${task.id}, executionStartedAt=$executionStartedAt, duration=${executionDuration}ms")
                            task.status = "PENDING"
                            task.updatedAt = now
                            backtestTaskRepository.save(task)
                        }
                    }
                } else {
                logger.debug("有 ${runningTasks.size} 个任务正在执行，跳过本次轮询")
                return
                }
            }
            val pendingTasks = backtestTaskRepository.findByStatus("PENDING")
                .sortedBy { it.createdAt }

            if (pendingTasks.isEmpty()) {
                return
            }
            val taskToExecute = pendingTasks.first()
            logger.info("找到 ${pendingTasks.size} 个待执行的回测任务，执行最早创建的任务: taskId=${taskToExecute.id}, createdAt=${taskToExecute.createdAt}")
            executor.submit {
                try {
                    val currentTask = backtestTaskRepository.findById(taskToExecute.id!!).orElse(null)
                    if (currentTask == null || currentTask.status != "PENDING") {
                        logger.debug("任务状态已变更，跳过执行: taskId=${taskToExecute.id}, currentStatus=${currentTask?.status}")
                        return@submit
                    }

                    runBlocking {
                        logger.info("执行回测任务: taskId=${currentTask.id}（游标分页，limit=500）")
                        executionService.executeBacktest(currentTask, page = 0, size = 500)
                    }
                } catch (e: Exception) {
                    logger.error("回测任务执行失败: taskId=${taskToExecute.id}", e)
                    val failedTask = backtestTaskRepository.findById(taskToExecute.id!!).orElse(null)
                    if (failedTask != null) {
                        failedTask.status = "FAILED"
                        failedTask.errorMessage = e.message
                        failedTask.updatedAt = System.currentTimeMillis()
                        backtestTaskRepository.save(failedTask)
                    }
                }
            }

        } catch (e: Exception) {
            logger.error("轮询回测任务失败", e)
        }
    }

}
