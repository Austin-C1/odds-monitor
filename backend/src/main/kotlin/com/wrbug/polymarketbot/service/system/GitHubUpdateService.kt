package com.wrbug.polymarketbot.service.system

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.wrbug.polymarketbot.controller.system.UpdateCheckResponse
import com.wrbug.polymarketbot.controller.system.UpdateStatusResponse
import com.wrbug.polymarketbot.util.createClient
import okhttp3.Request
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.io.File
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipInputStream
import kotlin.concurrent.thread

object UpdatePackageSafety {
    private val allowedExactPaths = setOf(
        "launch-odds-monitor.ps1",
        "launch-odds-monitor.cmd",
        "start-odds-backend.ps1",
        "start-odds-backend.cmd"
    )

    private val allowedPrefixes = listOf(
        "backend/build/libs/",
        "frontend/dist/",
        "scripts/",
        ".tools/"
    )

    private val protectedPrefixes = listOf(
        "data/",
        "config/",
        "logs/",
        "backups/",
        "updates/",
        "backend/logs/",
        "frontend/logs/"
    )

    private val protectedExactPaths = setOf(
        ".env",
        ".env.local",
        "docker-compose.prod.env",
        "backend-live.out.log",
        "backend-live.err.log",
        "frontend-live.out.log",
        "frontend-live.err.log",
        "frontend-18880-live.out.log",
        "frontend-18880-live.err.log"
    )

    fun isAllowedProgramPath(path: String): Boolean {
        val normalized = normalize(path) ?: return false
        if (normalized in protectedExactPaths) return false
        if (protectedPrefixes.any { normalized.startsWith(it) }) return false
        return normalized in allowedExactPaths || allowedPrefixes.any { normalized.startsWith(it) }
    }

    fun normalize(path: String): String? {
        val normalized = path.replace('\\', '/').trim().trimStart('/')
        if (normalized.isBlank()) return null
        if (path.startsWith("/") || path.startsWith("\\") || Regex("^[A-Za-z]:").containsMatchIn(path)) return null
        if (normalized.split('/').any { it == ".." || it.isBlank() }) return null
        return normalized
    }
}

object UpdateVersionComparator {
    fun isNewer(latest: String, current: String): Boolean {
        val latestParts = parse(latest)
        val currentParts = parse(current)
        val maxSize = maxOf(latestParts.size, currentParts.size)
        for (index in 0 until maxSize) {
            val a = latestParts.getOrElse(index) { 0 }
            val b = currentParts.getOrElse(index) { 0 }
            if (a > b) return true
            if (a < b) return false
        }
        return false
    }

    private fun parse(version: String): List<Int> {
        return version.trim().removePrefix("v").removePrefix("V")
            .split('-', '+')
            .firstOrNull()
            ?.split('.')
            ?.map { it.toIntOrNull() ?: 0 }
            ?: listOf(0)
    }
}

object GitHubReleaseApiUrlBuilder {
    fun latestReleaseApiUrl(repo: String): String {
        val ownerRepo = repo
            .removePrefix("https://github.com/")
            .removePrefix("http://github.com/")
            .trim('/')
        val encodedOwnerRepo = ownerRepo
            .split('/')
            .filter { it.isNotBlank() }
            .joinToString("/") { segment ->
                URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20")
            }
        return "https://api.github.com/repos/$encodedOwnerRepo/releases/latest"
    }
}

object OddsMonitorUpdateDefaults {
    const val GITHUB_REPO = "Austin-C1/odds-monitor"
}

object UpdateApplyScriptBuilder {
    fun render(
        appRoot: Path,
        packageRoot: Path,
        backupRoot: Path,
        files: List<String>,
        backendPid: Long
    ): String {
        val fileList = files.joinToString(",\n") { "  '${it.replace("'", "''")}'" }
        return """
            ${'$'}ErrorActionPreference = 'Stop'
            function Decode-Text([string]${'$'}Value) {
              return [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String(${'$'}Value))
            }
            ${'$'}appRoot = Decode-Text '${encode(appRoot.toAbsolutePath().toString())}'
            ${'$'}packageRoot = Decode-Text '${encode(packageRoot.toAbsolutePath().toString())}'
            ${'$'}backupRoot = Decode-Text '${encode(backupRoot.toAbsolutePath().toString())}'
            ${'$'}statusPath = Join-Path ${'$'}appRoot 'updates\update-status.json'
            ${'$'}files = @(
            $fileList
            )
            function Write-Status([int]${'$'}Progress, [string]${'$'}Message, [string]${'$'}ErrorMessage = ${'$'}null) {
              ${'$'}json = @{ updating = ${'$'}true; progress = ${'$'}Progress; message = ${'$'}Message; error = ${'$'}ErrorMessage } | ConvertTo-Json -Compress
              Set-Content -Path ${'$'}statusPath -Value ${'$'}json -Encoding UTF8
            }
            Start-Sleep -Seconds 2
            Write-Status 82 'Stopping old backend'
            Stop-Process -Id $backendPid -Force -ErrorAction SilentlyContinue
            Start-Sleep -Seconds 2
            New-Item -ItemType Directory -Path ${'$'}backupRoot -Force | Out-Null
            Write-Status 88 'Backing up old files'
            foreach (${'$'}relative in ${'$'}files) {
              ${'$'}src = Join-Path ${'$'}packageRoot ${'$'}relative
              ${'$'}dst = Join-Path ${'$'}appRoot ${'$'}relative
              if (Test-Path ${'$'}dst) {
                ${'$'}backup = Join-Path ${'$'}backupRoot ${'$'}relative
                New-Item -ItemType Directory -Path (Split-Path -Parent ${'$'}backup) -Force | Out-Null
                Copy-Item -LiteralPath ${'$'}dst -Destination ${'$'}backup -Recurse -Force
              }
            }
            Write-Status 94 'Copying new files'
            foreach (${'$'}relative in ${'$'}files) {
              ${'$'}src = Join-Path ${'$'}packageRoot ${'$'}relative
              ${'$'}dst = Join-Path ${'$'}appRoot ${'$'}relative
              New-Item -ItemType Directory -Path (Split-Path -Parent ${'$'}dst) -Force | Out-Null
              Copy-Item -LiteralPath ${'$'}src -Destination ${'$'}dst -Recurse -Force
            }
            ${'$'}json = @{ updating = ${'$'}false; progress = 100; message = 'Update completed'; error = ${'$'}null } | ConvertTo-Json -Compress
            Set-Content -Path ${'$'}statusPath -Value ${'$'}json -Encoding UTF8
            ${'$'}launcher = Join-Path ${'$'}appRoot 'launch-odds-monitor.ps1'
            if (Test-Path ${'$'}launcher) {
              Start-Process -FilePath powershell.exe -ArgumentList @('-NoProfile','-ExecutionPolicy','Bypass','-File',${'$'}launcher) -WorkingDirectory ${'$'}appRoot -WindowStyle Hidden
            }
        """.trimIndent()
    }

    private fun encode(value: String): String {
        return Base64.getEncoder().encodeToString(value.toByteArray(StandardCharsets.UTF_8))
    }
}

@Service
class GitHubUpdateService(
    private val objectMapper: ObjectMapper
) {
    private val logger = LoggerFactory.getLogger(GitHubUpdateService::class.java)
    private val updating = AtomicBoolean(false)
    private val status = AtomicReference(UpdateStatusResponse(false, 0, "就绪", null))
    private val client = createClient().followRedirects(true).followSslRedirects(true).build()

    fun getStatus(): UpdateStatusResponse {
        readPersistedStatus()?.let { persisted ->
            if (!updating.get()) status.set(persisted)
        }
        return status.get()
    }

    fun checkUpdate(currentVersion: String): UpdateCheckResponse {
        val release = fetchLatestRelease() ?: return UpdateCheckResponse(
            hasUpdate = false,
            currentVersion = currentVersion,
            latestVersion = currentVersion,
            latestTag = "v$currentVersion",
            releaseNotes = "未配置 GitHub 更新源",
            publishedAt = Instant.now().toString(),
            prerelease = false
        )

        val latestVersion = release.tag.removePrefix("v").removePrefix("V")
        return UpdateCheckResponse(
            hasUpdate = UpdateVersionComparator.isNewer(latestVersion, currentVersion),
            currentVersion = currentVersion,
            latestVersion = latestVersion,
            latestTag = release.tag,
            releaseNotes = release.body,
            publishedAt = release.publishedAt,
            prerelease = release.prerelease
        )
    }

    fun startUpdate(currentVersion: String): Result<Unit> {
        if (!updating.compareAndSet(false, true)) {
            return Result.failure(IllegalStateException("更新正在进行中"))
        }

        thread(name = "odds-monitor-github-update", isDaemon = true) {
            runCatching {
                val release = fetchLatestRelease() ?: throw IllegalStateException("未配置 GitHub 更新源")
                val latestVersion = release.tag.removePrefix("v").removePrefix("V")
                if (!UpdateVersionComparator.isNewer(latestVersion, currentVersion)) {
                    setStatus(false, 100, "当前已是最新版本", null)
                    return@thread
                }
                val asset = release.assets.firstOrNull {
                    it.name.endsWith(".zip", ignoreCase = true) && it.name.contains("update", ignoreCase = true)
                } ?: release.assets.firstOrNull { it.name.endsWith(".zip", ignoreCase = true) }
                    ?: throw IllegalStateException("GitHub Release 中没有更新包 zip")

                setStatus(true, 10, "下载更新包", null)
                val updateRoot = appRoot().resolve("updates")
                Files.createDirectories(updateRoot)
                val zipPath = updateRoot.resolve("${release.tag}-${asset.name}")
                download(asset.downloadUrl, zipPath)

                setStatus(true, 30, "解压更新包", null)
                val workDir = updateRoot.resolve("work-${release.tag}-${System.currentTimeMillis()}")
                unzip(zipPath, workDir)

                setStatus(true, 45, "校验更新包", null)
                val packageRoot = findPackageRoot(workDir)
                val files = validatePackage(packageRoot)

                setStatus(true, 65, "准备覆盖程序文件", null)
                val script = writeApplyScript(packageRoot, files, release.tag)

                setStatus(true, 80, "即将重启并应用更新", null)
                ProcessBuilder("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", script.toAbsolutePath().toString())
                    .directory(appRoot().toFile())
                    .start()
            }.onFailure { error ->
                logger.error("GitHub 更新失败: ${error.message}", error)
                setStatus(false, 0, "更新失败", error.message ?: "未知错误")
                updating.set(false)
            }
        }

        return Result.success(Unit)
    }

    private fun fetchLatestRelease(): GitHubRelease? {
        val apiUrl = releaseApiUrl() ?: return null
        val requestBuilder = Request.Builder()
            .url(apiUrl)
            .get()
            .header("Accept", "application/vnd.github+json")

        githubToken()?.let { requestBuilder.header("Authorization", "Bearer $it") }

        client.newCall(requestBuilder.build()).execute().use { response ->
            if (!response.isSuccessful) {
                logger.warn("GitHub release check failed: HTTP {}", response.code)
                return null
            }
            val root = objectMapper.readTree(response.body?.string().orEmpty())
            return GitHubRelease(
                tag = root.get("tag_name")?.asText().orEmpty(),
                body = root.get("body")?.asText().orEmpty(),
                publishedAt = root.get("published_at")?.asText() ?: Instant.now().toString(),
                prerelease = root.get("prerelease")?.asBoolean() ?: false,
                assets = root.get("assets")?.mapNotNull { asset ->
                    val name = asset.get("name")?.asText() ?: return@mapNotNull null
                    val url = asset.get("browser_download_url")?.asText() ?: return@mapNotNull null
                    GitHubReleaseAsset(name, url)
                }.orEmpty()
            )
        }
    }

    private fun releaseApiUrl(): String? {
        System.getenv("ODDS_MONITOR_UPDATE_RELEASE_API_URL")?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
        updateConfig()?.get("releaseApiUrl")?.asText()?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
        val repo = System.getenv("ODDS_MONITOR_GITHUB_REPO")?.trim()?.takeIf { it.isNotBlank() }
            ?: updateConfig()?.get("githubRepo")?.asText()?.trim()?.takeIf { it.isNotBlank() }
            ?: OddsMonitorUpdateDefaults.GITHUB_REPO
        return GitHubReleaseApiUrlBuilder.latestReleaseApiUrl(repo)
    }

    private fun githubToken(): String? {
        return System.getenv("ODDS_MONITOR_GITHUB_TOKEN")?.trim()?.takeIf { it.isNotBlank() }
            ?: System.getenv("GITHUB_TOKEN")?.trim()?.takeIf { it.isNotBlank() }
            ?: updateConfig()?.get("githubToken")?.asText()?.trim()?.takeIf { it.isNotBlank() }
    }

    private fun updateConfig(): JsonNode? {
        val configPath = appRoot().resolve("config").resolve("update.json")
        if (!Files.exists(configPath)) return null
        return runCatching { objectMapper.readTree(configPath.toFile()) }.getOrNull()
    }

    private fun download(url: String, target: Path) {
        val requestBuilder = Request.Builder().url(url).get()
        githubToken()?.let { requestBuilder.header("Authorization", "Bearer $it") }
        client.newCall(requestBuilder.build()).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("下载更新包失败: HTTP ${response.code}")
            response.body?.byteStream()?.use { input ->
                Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING)
            } ?: throw IllegalStateException("下载更新包失败: 空响应")
        }
    }

    private fun unzip(zipPath: Path, targetDir: Path) {
        Files.createDirectories(targetDir)
        ZipInputStream(Files.newInputStream(zipPath)).use { zip ->
            generateSequence { zip.nextEntry }.forEach { entry ->
                val target = targetDir.resolve(entry.name).normalize()
                if (!target.startsWith(targetDir)) throw IllegalStateException("更新包包含非法路径: ${entry.name}")
                if (entry.isDirectory) {
                    Files.createDirectories(target)
                } else {
                    Files.createDirectories(target.parent)
                    Files.copy(zip, target, StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }
    }

    private fun findPackageRoot(workDir: Path): Path {
        val directManifest = workDir.resolve("odds-monitor-update.json")
        if (Files.exists(directManifest)) return workDir
        Files.walk(workDir, 3).use { stream ->
            return stream.filter { Files.isRegularFile(it) && it.fileName.toString() == "odds-monitor-update.json" }
                .findFirst()
                .map { it.parent }
                .orElseThrow { IllegalStateException("更新包缺少 odds-monitor-update.json") }
        }
    }

    private fun validatePackage(packageRoot: Path): List<String> {
        val manifest = objectMapper.readTree(packageRoot.resolve("odds-monitor-update.json").toFile())
        val app = manifest.get("app")?.asText()
        require(app == "odds-monitor") { "更新包不是全平台赔率监控更新包" }

        val files = Files.walk(packageRoot).use { stream ->
            stream.filter { Files.isRegularFile(it) }
                .map { packageRoot.relativize(it).toString().replace(File.separatorChar, '/') }
                .filter { it != "odds-monitor-update.json" }
                .toList()
        }
        require(files.isNotEmpty()) { "更新包没有可更新文件" }
        val blocked = files.filterNot { UpdatePackageSafety.isAllowedProgramPath(it) }
        require(blocked.isEmpty()) { "更新包包含禁止覆盖的路径: ${blocked.joinToString(", ")}" }
        return files
    }

    private fun writeApplyScript(packageRoot: Path, files: List<String>, tag: String): Path {
        val updateRoot = appRoot().resolve("updates")
        val script = updateRoot.resolve("apply-$tag.ps1")
        val backupRoot = appRoot().resolve("backups").resolve("update-${DateTimeFormatter.ISO_INSTANT.format(Instant.now()).replace(':', '-')}")
        val content = UpdateApplyScriptBuilder.render(
            appRoot = appRoot(),
            packageRoot = packageRoot,
            backupRoot = backupRoot,
            files = files,
            backendPid = ProcessHandle.current().pid()
        )
        Files.writeString(script, content, StandardCharsets.UTF_8)
        return script
    }
    private fun readPersistedStatus(): UpdateStatusResponse? {
        val statusPath = appRoot().resolve("updates").resolve("update-status.json")
        if (!Files.exists(statusPath)) return null
        return runCatching {
            val node = objectMapper.readTree(statusPath.toFile())
            UpdateStatusResponse(
                updating = node.get("updating")?.asBoolean() ?: false,
                progress = node.get("progress")?.asInt() ?: 0,
                message = node.get("message")?.asText().orEmpty(),
                error = node.get("error")?.takeUnless(JsonNode::isNull)?.asText()
            )
        }.getOrNull()
    }

    private fun setStatus(isUpdating: Boolean, progress: Int, message: String, error: String?) {
        val next = UpdateStatusResponse(isUpdating, progress, message, error)
        status.set(next)
        runCatching {
            val updateRoot = appRoot().resolve("updates")
            Files.createDirectories(updateRoot)
            objectMapper.writeValue(updateRoot.resolve("update-status.json").toFile(), next)
        }
    }

    private fun appRoot(): Path {
        System.getenv("ODDS_MONITOR_APP_ROOT")?.trim()?.takeIf { it.isNotBlank() }?.let { return Path.of(it).toAbsolutePath().normalize() }
        val userDir = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize()
        return if (userDir.fileName?.toString() == "backend") userDir.parent else userDir
    }
}

private data class GitHubRelease(
    val tag: String,
    val body: String,
    val publishedAt: String,
    val prerelease: Boolean,
    val assets: List<GitHubReleaseAsset>
)

private data class GitHubReleaseAsset(
    val name: String,
    val downloadUrl: String
)
