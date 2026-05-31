package com.wrbug.polymarketbot.service.oddsmonitor.collector.crown

import com.fasterxml.jackson.databind.ObjectMapper
import com.wrbug.polymarketbot.entity.OddsDataSourceConfig
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path

@Component
class CrownSessionStore @Autowired constructor(
    private val objectMapper: ObjectMapper,
    @Value("\${crown.session.dir:output/crown/session}")
    sessionDirValue: String
) {
    constructor(objectMapper: ObjectMapper, sessionDir: Path) : this(objectMapper, sessionDir.toString())

    private val sessionDir: Path = Path.of(sessionDirValue)
    private val sessionPath: Path = sessionDir.resolve("session.json")

    @Synchronized
    fun load(config: OddsDataSourceConfig): CrownSession? {
        if (!Files.exists(sessionPath)) {
            return null
        }
        return runCatching {
            objectMapper.readValue(sessionPath.toFile(), CrownSession::class.java)
        }.getOrNull()?.takeIf { it.matches(config) }
    }

    @Synchronized
    fun save(config: OddsDataSourceConfig, session: CrownSession) {
        Files.createDirectories(sessionDir)
        val normalized = session.copy(
            username = config.username?.trim(),
            baseUrl = config.crownBaseUrl()
        )
        objectMapper.writeValue(sessionPath.toFile(), normalized)
    }

    @Synchronized
    fun invalidate() {
        Files.deleteIfExists(sessionPath)
    }

    private fun CrownSession.matches(config: OddsDataSourceConfig): Boolean {
        return uid.isNotBlank() &&
            username == config.username?.trim() &&
            baseUrl == config.crownBaseUrl()
    }
}

internal fun OddsDataSourceConfig.crownBaseUrl(): String {
    val configured = queryKeyword?.trim()?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
    return configured
        ?: throw CrownCollectionException("failed_config", "crown platform url is empty")
}
