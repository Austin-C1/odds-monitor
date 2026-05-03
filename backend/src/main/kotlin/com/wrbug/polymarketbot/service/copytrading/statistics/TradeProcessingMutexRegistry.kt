package com.wrbug.polymarketbot.service.copytrading.statistics

import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

internal class TradeProcessingMutexRegistry {

    private data class MutexHolder(
        val mutex: Mutex,
        val references: AtomicInteger
    )

    internal data class MutexLease(
        val mutex: Mutex,
        val release: () -> Unit
    )

    private val holders = ConcurrentHashMap<String, MutexHolder>()

    fun acquire(key: String): MutexLease {
        val holder = holders.compute(key) { _, existing ->
            existing?.apply { references.incrementAndGet() }
                ?: MutexHolder(Mutex(), AtomicInteger(1))
        }!!

        return MutexLease(
            mutex = holder.mutex,
            release = {
                holders.computeIfPresent(key) { _, current ->
                    if (current.references.decrementAndGet() <= 0) {
                        null
                    } else {
                        current
                    }
                }
            }
        )
    }

    internal fun activeKeyCount(): Int = holders.size
}
