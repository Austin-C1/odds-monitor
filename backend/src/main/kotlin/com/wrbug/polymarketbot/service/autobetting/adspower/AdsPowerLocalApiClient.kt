package com.wrbug.polymarketbot.service.autobetting.adspower

import com.fasterxml.jackson.databind.ObjectMapper
import com.wrbug.polymarketbot.dto.AdsPowerBrowserSessionDto
import com.wrbug.polymarketbot.dto.AdsPowerProfileActiveDto
import com.wrbug.polymarketbot.dto.AdsPowerStatusDto
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URI
import java.time.Duration

const val DEFAULT_ADSPOWER_BASE_URL = "http://127.0.0.1:50325"

class AdsPowerLocalApiClient(
    private val objectMapper: ObjectMapper,
    private val baseUrl: String = DEFAULT_ADSPOWER_BASE_URL,
    private val apiKey: String? = null
) {
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(Duration.ofSeconds(3))
        .readTimeout(Duration.ofSeconds(8))
        .writeTimeout(Duration.ofSeconds(8))
        .build()

    fun checkStatus(now: Long = System.currentTimeMillis()): AdsPowerStatusDto {
        val endpoint = buildUrl("/status") ?: return AdsPowerStatusDto(
            available = false,
            baseUrl = normalizedBaseUrl(),
            message = "invalid_adspower_base_url",
            checkedAt = now
        )
        val request = requestBuilder(endpoint).get().build()

        return try {
            httpClient.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                val root = body.takeIf { it.isNotBlank() }?.let { objectMapper.readTree(it) }
                val code = root?.path("code")?.takeIf { !it.isMissingNode && !it.isNull }?.asInt()
                val message = root?.path("msg")?.takeIf { !it.isMissingNode && !it.isNull }?.asText()
                    ?: if (response.isSuccessful) "success" else "http_${response.code}"
                AdsPowerStatusDto(
                    available = response.isSuccessful && code == 0,
                    baseUrl = normalizedBaseUrl(),
                    code = code,
                    message = message,
                    checkedAt = now
                )
            }
        } catch (error: Exception) {
            AdsPowerStatusDto(
                available = false,
                baseUrl = normalizedBaseUrl(),
                code = null,
                message = error.message ?: "adspower_unreachable",
                checkedAt = now
            )
        }
    }

    fun startProfile(profileId: String, now: Long = System.currentTimeMillis()): AdsPowerBrowserSessionDto {
        val normalizedProfileId = profileId.trim()
        if (normalizedProfileId.isBlank()) {
            return AdsPowerBrowserSessionDto(
                profileId = "",
                opened = false,
                message = "profile_id_required",
                openedAt = now
            )
        }

        val byUserId = startProfileBy("user_id", normalizedProfileId, normalizedProfileId, now)
        if (byUserId.opened || !normalizedProfileId.isAdsPowerSerialNumber()) {
            return byUserId
        }
        val bySerialNumber = startProfileBy("serial_number", normalizedProfileId, normalizedProfileId, now)
        return if (bySerialNumber.opened) bySerialNumber else byUserId
    }

    fun checkProfileActive(profileId: String, now: Long = System.currentTimeMillis()): AdsPowerProfileActiveDto {
        val normalizedProfileId = profileId.trim()
        if (normalizedProfileId.isBlank()) {
            return AdsPowerProfileActiveDto(
                profileId = "",
                opened = false,
                message = "profile_id_required",
                checkedAt = now
            )
        }

        val byUserId = checkProfileActiveBy("user_id", normalizedProfileId, normalizedProfileId, now)
        if (byUserId.opened || !normalizedProfileId.isAdsPowerSerialNumber()) {
            return byUserId
        }
        val bySerialNumber = checkProfileActiveBy("serial_number", normalizedProfileId, normalizedProfileId, now)
        return if (bySerialNumber.opened) bySerialNumber else byUserId
    }

    private fun startProfileBy(
        parameterName: String,
        parameterValue: String,
        profileId: String,
        now: Long
    ): AdsPowerBrowserSessionDto {
        val endpoint = buildUrl("/api/v1/browser/start")
            ?.newBuilder()
            ?.addQueryParameter(parameterName, parameterValue)
            ?.addQueryParameter("open_tabs", "1")
            ?.addQueryParameter("ip_tab", "1")
            ?.addQueryParameter("headless", "0")
            ?.build()
            ?: return AdsPowerBrowserSessionDto(
                profileId = profileId,
                opened = false,
                message = "invalid_adspower_base_url",
                openedAt = now
            )
        val request = requestBuilder(endpoint).get().build()

        return try {
            httpClient.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                val root = body.takeIf { it.isNotBlank() }?.let { objectMapper.readTree(it) }
                val code = root?.path("code")?.takeIf { !it.isMissingNode && !it.isNull }?.asInt()
                val message = root?.path("msg")?.takeIf { !it.isMissingNode && !it.isNull }?.asText()
                    ?: if (response.isSuccessful) "success" else "http_${response.code}"
                val data = root?.path("data")
                AdsPowerBrowserSessionDto(
                    profileId = profileId,
                    opened = response.isSuccessful && code == 0,
                    message = message,
                    debugPort = data.debugPortOrNull(),
                    openedAt = now
                )
            }
        } catch (error: Exception) {
            AdsPowerBrowserSessionDto(
                profileId = profileId,
                opened = false,
                message = error.message ?: "adspower_start_failed",
                openedAt = now
            )
        }
    }

    private fun checkProfileActiveBy(
        parameterName: String,
        parameterValue: String,
        profileId: String,
        now: Long
    ): AdsPowerProfileActiveDto {
        val endpoint = buildUrl("/api/v1/browser/active")
            ?.newBuilder()
            ?.addQueryParameter(parameterName, parameterValue)
            ?.build()
            ?: return AdsPowerProfileActiveDto(
                profileId = profileId,
                opened = false,
                message = "invalid_adspower_base_url",
                checkedAt = now
            )
        val request = requestBuilder(endpoint).get().build()

        return try {
            httpClient.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                val root = body.takeIf { it.isNotBlank() }?.let { objectMapper.readTree(it) }
                val code = root?.path("code")?.takeIf { !it.isMissingNode && !it.isNull }?.asInt()
                val message = root?.path("msg")?.takeIf { !it.isMissingNode && !it.isNull }?.asText()
                    ?: if (response.isSuccessful) "success" else "http_${response.code}"
                val data = root?.path("data")
                val status = data?.path("status")?.textOrNull()
                AdsPowerProfileActiveDto(
                    profileId = profileId,
                    opened = response.isSuccessful && code == 0 && status?.equals("Active", ignoreCase = true) == true,
                    message = message,
                    status = status,
                    debugPort = data.debugPortOrNull(),
                    checkedAt = now
                )
            }
        } catch (error: Exception) {
            AdsPowerProfileActiveDto(
                profileId = profileId,
                opened = false,
                message = error.message ?: "adspower_profile_active_failed",
                checkedAt = now
            )
        }
    }

    internal fun listLocalActiveProfiles(now: Long): List<AdsPowerActiveProfile> {
        val endpoint = buildUrl("/api/v1/browser/local-active") ?: return emptyList()
        val request = requestBuilder(endpoint).get().build()
        return try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return emptyList()
                val root = objectMapper.readTree(response.body?.string().orEmpty())
                if (root.path("code").asInt(-1) != 0) return emptyList()
                root.path("data").path("list").takeIf { it.isArray }?.mapNotNull { node ->
                    val profileId = node.path("user_id").textOrNull() ?: return@mapNotNull null
                    val debugPort = node.path("debug_port").textOrNull()
                        ?: parseDebugPort(node.path("ws").path("selenium").textOrNull())
                    AdsPowerActiveProfile(profileId, debugPort, now)
                }.orEmpty()
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    internal fun loadProfileMetadata(profileIds: List<String>): Map<String, AdsPowerProfileMetadata> {
        if (profileIds.isEmpty()) return emptyMap()
        val metadata = mutableMapOf<String, AdsPowerProfileMetadata>()
        profileIds.distinct().chunked(25).forEach { chunk ->
            chunk.forEach profileLoop@{ profileId ->
                val endpoint = buildUrl("/api/v1/user/list")
                    ?.newBuilder()
                    ?.addQueryParameter("user_id", profileId)
                    ?.addQueryParameter("page_size", "1")
                    ?.build() ?: return@profileLoop
                val request = requestBuilder(endpoint).get().build()
                val profile = try {
                    httpClient.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) return@use null
                        val root = objectMapper.readTree(response.body?.string().orEmpty())
                        if (root.path("code").asInt(-1) != 0) return@use null
                        val node = root.path("data").path("list").firstOrNull() ?: return@use null
                        AdsPowerProfileMetadata(
                            profileId = node.path("user_id").textOrNull() ?: profileId,
                            serialNumber = node.path("serial_number").textOrNull(),
                            name = node.path("name").textOrNull(),
                            username = node.path("username").textOrNull(),
                            remark = node.path("remark").textOrNull()
                        )
                    }
                } catch (_: Exception) {
                    null
                }
                if (profile != null) {
                    metadata[profile.profileId] = profile
                }
            }
        }
        return metadata
    }

    internal fun loadProfileMetadataPage(): List<AdsPowerProfileMetadata> {
        val endpoint = buildUrl("/api/v1/user/list")
            ?.newBuilder()
            ?.addQueryParameter("page", "1")
            ?.addQueryParameter("page_size", "100")
            ?.build() ?: return emptyList()
        val request = requestBuilder(endpoint).get().build()
        return try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return emptyList()
                val root = objectMapper.readTree(response.body?.string().orEmpty())
                if (root.path("code").asInt(-1) != 0) return emptyList()
                root.path("data").path("list").takeIf { it.isArray }?.mapNotNull { node ->
                    val profileId = node.path("user_id").textOrNull() ?: return@mapNotNull null
                    AdsPowerProfileMetadata(
                        profileId = profileId,
                        serialNumber = node.path("serial_number").textOrNull(),
                        name = node.path("name").textOrNull(),
                        username = node.path("username").textOrNull(),
                        remark = node.path("remark").textOrNull()
                    )
                }.orEmpty()
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun parseDebugPort(value: String?): String? {
        val raw = value?.trim()?.takeIf { it.isNotBlank() } ?: return null
        raw.toIntOrNull()?.takeIf { it in 1..65535 }?.let { return it.toString() }
        runCatching { URI(raw).port }
            .getOrNull()
            ?.takeIf { it in 1..65535 }
            ?.let { return it.toString() }
        return raw.substringAfterLast(':')
            .substringBefore('/')
            .takeIf { it.toIntOrNull() in 1..65535 }
    }

    private fun buildUrl(path: String): okhttp3.HttpUrl? {
        val base = normalizedBaseUrl().toHttpUrlOrNull() ?: return null
        if (!isLocalHost(base.host)) return null
        return "${normalizedBaseUrl()}$path".toHttpUrlOrNull()
    }

    private fun requestBuilder(url: okhttp3.HttpUrl): Request.Builder {
        val builder = Request.Builder().url(url)
        val token = apiKey?.trim().orEmpty()
        if (token.isNotBlank()) {
            builder.header("Authorization", "Bearer $token")
        }
        return builder
    }

    private fun normalizedBaseUrl() = baseUrl.trim().trimEnd('/')

    private fun isLocalHost(host: String): Boolean {
        val normalized = host.trim().lowercase().removeSurrounding("[", "]")
        return normalized == "127.0.0.1" || normalized == "localhost" || normalized == "::1"
    }

    private fun com.fasterxml.jackson.databind.JsonNode.textOrNull(): String? {
        return takeIf { !it.isMissingNode && !it.isNull }?.asText()?.takeIf { it.isNotBlank() }
    }

    private fun com.fasterxml.jackson.databind.JsonNode?.debugPortOrNull(): String? {
        val data = this ?: return null
        return data.path("debug_port").textOrNull()
            ?: parseDebugPort(data.path("ws").path("selenium").textOrNull())
            ?: parseDebugPort(data.path("ws").path("puppeteer").textOrNull())
    }

    private fun String.isAdsPowerSerialNumber(): Boolean {
        return isNotBlank() && all { it.isDigit() }
    }
}

internal data class AdsPowerActiveProfile(
    val profileId: String,
    val debugPort: String?,
    val checkedAt: Long
)

internal data class AdsPowerProfileMetadata(
    val profileId: String,
    val serialNumber: String?,
    val name: String?,
    val username: String?,
    val remark: String?
)
