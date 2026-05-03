package com.wrbug.polymarketbot.service.copytrading.monitor

class ActivityStreamHealthTracker(
    private val timeoutMillis: Long
) {
    @Volatile
    private var lastMessageAt: Long = 0L

    fun markMessage(now: Long = System.currentTimeMillis()) {
        lastMessageAt = now
    }

    fun reset() {
        lastMessageAt = 0L
    }

    fun shouldReconnect(now: Long = System.currentTimeMillis()): Boolean {
        val lastSeen = lastMessageAt
        return lastSeen > 0L && now - lastSeen >= timeoutMillis
    }
}
