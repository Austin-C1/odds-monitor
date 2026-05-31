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

    fun <T> withAccountLock(accountKey: String, profileId: String, action: () -> T): T {
        val normalizedAccountKey = accountKey.trim().lowercase()
        val key = if (normalizedAccountKey.isNotBlank()) {
            "account:$normalizedAccountKey"
        } else {
            profileId.trim().lowercase().takeIf { it.isNotBlank() }?.let { "profile:$it" }.orEmpty()
        }
        if (key.isBlank()) return action()
        val lock = locks.computeIfAbsent(key) { ReentrantLock() }
        return lock.withLock(action)
    }
}
