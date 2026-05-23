package com.wrbug.polymarketbot.service.autobetting

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class CrownProfileExecutionLockTest {
    @Test
    fun `same profile executions run one at a time`() {
        val lock = CrownProfileExecutionLock()
        val executor = Executors.newFixedThreadPool(4)
        val started = CountDownLatch(4)
        val release = CountDownLatch(1)
        val done = CountDownLatch(4)
        val active = AtomicInteger(0)
        val maxActive = AtomicInteger(0)

        repeat(4) {
            executor.submit {
                started.countDown()
                started.await(3, TimeUnit.SECONDS)
                lock.withProfileLock("profile-a") {
                    val current = active.incrementAndGet()
                    maxActive.updateAndGet { previous -> maxOf(previous, current) }
                    release.await(3, TimeUnit.SECONDS)
                    active.decrementAndGet()
                }
                done.countDown()
            }
        }

        started.await(3, TimeUnit.SECONDS)
        Thread.sleep(150)
        assertEquals(1, maxActive.get())
        release.countDown()
        done.await(3, TimeUnit.SECONDS)
        executor.shutdownNow()
    }

    @Test
    fun `different profiles can execute independently`() {
        val lock = CrownProfileExecutionLock()
        val executor = Executors.newFixedThreadPool(2)
        val release = CountDownLatch(1)
        val entered = CountDownLatch(2)

        listOf("profile-a", "profile-b").forEach { profileId ->
            executor.submit {
                lock.withProfileLock(profileId) {
                    entered.countDown()
                    release.await(3, TimeUnit.SECONDS)
                }
            }
        }

        assertEquals(true, entered.await(1, TimeUnit.SECONDS))
        release.countDown()
        executor.shutdownNow()
    }
}
