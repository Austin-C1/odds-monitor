package com.wrbug.polymarketbot.service.oddsmonitor.collector.crown

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.wrbug.polymarketbot.entity.OddsDataSourceConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class CrownSessionStoreTest {
    @TempDir
    lateinit var tempDir: Path

    private val objectMapper = jacksonObjectMapper()

    @Test
    fun `saves and loads matching crown session`() {
        val store = CrownSessionStore(objectMapper, tempDir)
        val config = crownConfig(username = "alice", baseUrl = "https://crown.example")
        val session = CrownSession(
            uid = "uid-1",
            cookies = mapOf("SESSION" to "abc"),
            username = "alice",
            baseUrl = "https://crown.example",
            savedAt = 1000L
        )

        store.save(config, session)

        assertEquals(session, store.load(config))
    }

    @Test
    fun `ignores saved session for a different account or host`() {
        val store = CrownSessionStore(objectMapper, tempDir)
        val config = crownConfig(username = "alice", baseUrl = "https://crown.example")
        store.save(
            config,
            CrownSession(
                uid = "uid-1",
                cookies = mapOf("SESSION" to "abc"),
                username = "alice",
                baseUrl = "https://crown.example",
                savedAt = 1000L
            )
        )

        assertNull(store.load(crownConfig(username = "bob", baseUrl = "https://crown.example")))
        assertNull(store.load(crownConfig(username = "alice", baseUrl = "https://other.example")))
    }

    @Test
    fun `invalidates saved crown session`() {
        val store = CrownSessionStore(objectMapper, tempDir)
        val config = crownConfig(username = "alice", baseUrl = "https://crown.example")
        store.save(
            config,
            CrownSession(
                uid = "uid-1",
                cookies = mapOf("SESSION" to "abc"),
                username = "alice",
                baseUrl = "https://crown.example",
                savedAt = 1000L
            )
        )

        store.invalidate()

        assertNull(store.load(config))
        assertEquals(false, Files.exists(tempDir.resolve("session.json")))
    }

    @Test
    fun `requires crown base url to be configured instead of falling back to a mirror`() {
        val config = OddsDataSourceConfig(
            sourceKey = "crown",
            displayName = "Crown",
            enabled = true,
            username = "alice",
            password = "secret",
            queryKeyword = null
        )

        val exception = assertThrows(CrownCollectionException::class.java) {
            config.crownBaseUrl()
        }

        assertEquals("failed_config", exception.status)
    }

    private fun crownConfig(username: String, baseUrl: String) = OddsDataSourceConfig(
        sourceKey = "crown",
        displayName = "Crown",
        enabled = true,
        username = username,
        password = "secret",
        queryKeyword = baseUrl
    )
}
