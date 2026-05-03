package com.wrbug.polymarketbot.util

import java.util.concurrent.atomic.AtomicBoolean

class SingleFlightGate {
    private val entered = AtomicBoolean(false)

    fun tryEnter(): Boolean = entered.compareAndSet(false, true)

    fun leave() {
        entered.set(false)
    }
}
