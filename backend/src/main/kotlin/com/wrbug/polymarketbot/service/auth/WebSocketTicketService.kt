package com.wrbug.polymarketbot.service.auth

import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

@Service
class WebSocketTicketService {

    companion object {
        private const val TICKET_VALIDITY_MS = 30_000L
        private const val TICKET_LENGTH = 32
    }

    private val secureRandom = SecureRandom()
    private val tickets = ConcurrentHashMap<String, TicketInfo>()

    data class TicketInfo(
        val username: String,
        val createdAt: Long,
        val expiresAt: Long
    )

    fun generateTicket(username: String): String {
        cleanupExpiredTickets()
        val bytes = ByteArray(TICKET_LENGTH)
        secureRandom.nextBytes(bytes)
        val ticket = bytes.joinToString("") { "%02x".format(it) }

        val now = System.currentTimeMillis()
        tickets[ticket] = TicketInfo(
            username = username,
            createdAt = now,
            expiresAt = now + TICKET_VALIDITY_MS
        )

        return ticket
    }

    fun validateAndConsumeTicket(ticket: String): String? {
        val ticketInfo = tickets.remove(ticket) ?: return null
        if (System.currentTimeMillis() > ticketInfo.expiresAt) {
            return null
        }

        return ticketInfo.username
    }

    private fun cleanupExpiredTickets() {
        val now = System.currentTimeMillis()
        tickets.entries.removeIf { it.value.expiresAt < now }
    }
    
    @Scheduled(fixedRate = 60_000)
    fun scheduledCleanup() {
        cleanupExpiredTickets()
    }
}
