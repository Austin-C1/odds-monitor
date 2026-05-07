package com.wrbug.polymarketbot.service.oddsmonitor.collector.crown

import com.wrbug.polymarketbot.entity.OddsDataSourceConfig
import org.springframework.stereotype.Component

@Component
class CrownSessionManager(
    private val sessionStore: CrownSessionStore,
    private val gateway: CrownMatchGateway
) {
    @Synchronized
    fun fetchMatches(config: OddsDataSourceConfig): List<CrownFootballMatch> {
        val cachedSession = sessionStore.load(config)
        if (cachedSession != null) {
            try {
                return fetchAndSave(config, cachedSession).matches
            } catch (ex: CrownCollectionException) {
                if (ex.status != "failed_login") {
                    throw ex
                }
                sessionStore.invalidate()
            }
        }

        val freshSession = gateway.login(config)
        sessionStore.save(config, freshSession)
        return try {
            fetchAndSave(config, freshSession).matches
        } catch (ex: CrownCollectionException) {
            if (ex.status == "failed_login") {
                sessionStore.invalidate()
            }
            throw ex
        }
    }

    private fun fetchAndSave(config: OddsDataSourceConfig, session: CrownSession): CrownFetchResult {
        val result = gateway.fetchMatchesWithSession(config, session)
        sessionStore.save(config, result.session)
        return result
    }
}
