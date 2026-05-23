package com.wrbug.polymarketbot.service.autobetting

import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

@Component
class CrownProfileExecutionLock {
    private val locks = ConcurrentHashMap<String, ReentrantLock>()

    fun <T> withProfileLock(profileId: String, action: () -> T): T {
        val key = profileId.trim().lowercase()
        if (key.isBlank()) return action()
        val lock = locks.computeIfAbsent(key) { ReentrantLock() }
        return lock.withLock(action)
    }
}
