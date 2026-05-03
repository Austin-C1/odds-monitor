package com.wrbug.polymarketbot.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class SingleFlightGateTest {

    @Test
    fun `only one caller can enter until the gate is released`() {
        val gate = SingleFlightGate()

        assertTrue(gate.tryEnter())
        assertFalse(gate.tryEnter())

        gate.leave()

        assertTrue(gate.tryEnter())
    }

    @Test
    fun `concurrent callers still grant only one entry`() {
        val gate = SingleFlightGate()
        val ready = CountDownLatch(8)
        val start = CountDownLatch(1)
        val done = CountDownLatch(8)
        val granted = AtomicInteger(0)

        repeat(8) {
            thread(start = true) {
                ready.countDown()
                start.await()
                if (gate.tryEnter()) {
                    granted.incrementAndGet()
                }
                done.countDown()
            }
        }

        ready.await()
        start.countDown()
        done.await()

        assertEquals(1, granted.get())
    }
}
