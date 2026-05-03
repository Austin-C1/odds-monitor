package com.wrbug.polymarketbot.service.auth

import com.wrbug.polymarketbot.entity.User
import com.wrbug.polymarketbot.repository.UserRepository
import com.wrbug.polymarketbot.service.common.RateLimitService
import com.wrbug.polymarketbot.util.JwtUtils
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class AuthServiceDefaultAdminTest {

    private val userRepository = mock(UserRepository::class.java)
    private val jwtUtils = mock(JwtUtils::class.java)
    private val rateLimitService = mock(RateLimitService::class.java)
    private val passwordEncoder = BCryptPasswordEncoder()

    @Test
    fun `isFirstUse seeds packaged default admin when enabled`() {
        val store = InMemoryUserStore()
        val service = createService(store)

        setField(service, "packagedDefaultAdminEnabled", true)
        setField(service, "packagedDefaultAdminUsername", "123456")
        setField(service, "packagedDefaultAdminPassword", "123456")

        val result = service.isFirstUse()

        assertFalse(result)
        val savedUser = store.user ?: error("expected seeded user")
        assertEquals("123456", savedUser.username)
        assertTrue(savedUser.isDefault)
        assertTrue(passwordEncoder.matches("123456", savedUser.password))
    }

    @Test
    fun `login succeeds with packaged default admin credentials when repository is empty`() {
        val store = InMemoryUserStore()
        val service = createService(store)

        setField(service, "packagedDefaultAdminEnabled", true)
        setField(service, "packagedDefaultAdminUsername", "123456")
        setField(service, "packagedDefaultAdminPassword", "123456")

        `when`(rateLimitService.checkLoginRateLimit("127.0.0.1")).thenReturn(Result.success(Unit))
        `when`(jwtUtils.generateToken("123456", 0)).thenReturn("packaged-token")

        val result = service.login("123456", "123456", "127.0.0.1")

        assertTrue(result.isSuccess)
        assertEquals("packaged-token", result.getOrThrow().token)
        verify(rateLimitService).clearLoginFailures("127.0.0.1")
    }

    @Test
    fun `local login creates default admin and returns token`() {
        val store = InMemoryUserStore()
        val service = createService(store)

        setField(service, "packagedDefaultAdminEnabled", true)
        setField(service, "packagedDefaultAdminUsername", "123456")
        setField(service, "packagedDefaultAdminPassword", "123456")
        `when`(jwtUtils.generateToken("123456", 0)).thenReturn("local-token")

        val result = service.localLogin()

        assertTrue(result.isSuccess)
        assertEquals("local-token", result.getOrThrow().token)
        assertEquals("123456", store.user?.username)
    }

    @Test
    fun `concurrent first use checks seed packaged default admin only once`() {
        val store = InMemoryUserStore(barrier = CyclicBarrier(2))
        val service = createService(store)
        val executor = Executors.newFixedThreadPool(2)

        setField(service, "packagedDefaultAdminEnabled", true)
        setField(service, "packagedDefaultAdminUsername", "123456")
        setField(service, "packagedDefaultAdminPassword", "123456")

        try {
            val futures = listOf(
                executor.submit(Callable { service.isFirstUse() }),
                executor.submit(Callable { service.isFirstUse() })
            )

            val results = futures.map { it.get(10, TimeUnit.SECONDS) }

            assertEquals(listOf(false, false), results)
            assertEquals(1, store.saveCount)
        } finally {
            executor.shutdownNow()
        }
    }

    private fun createService(store: InMemoryUserStore): AuthService {
        `when`(userRepository.count()).thenAnswer { store.count() }
        `when`(userRepository.findByUsername(anyString())).thenAnswer {
            val username = it.getArgument<String>(0)
            store.findByUsername(username)
        }
        `when`(userRepository.findByIsDefaultTrue()).thenAnswer {
            store.user?.takeIf { it.isDefault }
        }
        `when`(
            userRepository.save(
                any(User::class.java) ?: User(username = "__stub__", password = "__stub__")
            )
        ).thenAnswer {
            val user = it.getArgument<User>(0)
            store.save(user)
        }

        return AuthService(
            userRepository = userRepository,
            jwtUtils = jwtUtils,
            rateLimitService = rateLimitService
        )
    }

    private fun setField(target: Any, name: String, value: Any) {
        val field = target.javaClass.getDeclaredField(name)
        field.isAccessible = true
        field.set(target, value)
    }

    private class InMemoryUserStore {
        constructor() : this(null)

        constructor(barrier: CyclicBarrier?) {
            this.barrier = barrier
        }

        var user: User? = null
        var saveCount: Int = 0
        private val barrier: CyclicBarrier?
        private val countCalls = AtomicInteger(0)

        fun count(): Long {
            if (barrier != null && countCalls.incrementAndGet() <= 2) {
                barrier.await(5, TimeUnit.SECONDS)
            }
            return if (user == null) 0L else 1L
        }

        fun findByUsername(username: String): User? = user?.takeIf { it.username == username }

        @Synchronized
        fun save(value: User): User {
            if (user != null) {
                throw IllegalStateException("duplicate default admin seed")
            }
            saveCount += 1
            user = if (value.id == null) value.copy(id = 1L) else value
            return user!!
        }
    }
}
