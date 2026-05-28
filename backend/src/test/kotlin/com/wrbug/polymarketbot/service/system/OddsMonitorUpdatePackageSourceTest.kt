package com.wrbug.polymarketbot.service.system

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class OddsMonitorUpdatePackageSourceTest {
    @Test
    fun `update package preserves old install data and carries migrations`() {
        val packageScript = readRootFile("build-odds-monitor-update-package.ps1")
        val launchScript = readRootFile("launch-odds-monitor.ps1")
        val startScript = readRootFile("start-odds-backend.ps1")

        assertTrue(
            packageScript.contains("Java runtime files are not allowed in update packages"),
            "update packages must stay separate from full install packages"
        )
        assertTrue(
            packageScript.contains("odds-monitor-backend-${'$'}version.jar"),
            "update package must include the backend jar so old databases receive Flyway migrations"
        )
        assertTrue(packageScript.contains("launch-odds-monitor.cmd"))
        assertTrue(packageScript.contains("start-odds-backend.ps1"))
        assertTrue(packageScript.contains("scripts\\serve-odds-frontend.ps1"))
        assertTrue(packageScript.contains("frontend') -Force"))
        assertTrue(
            packageScript.contains("assertPackagedAssetContains"),
            "update package must verify packaged frontend assets include current visible features"
        )
        assertTrue(packageScript.contains("5rWL6K+V5qih5byP"))
        assertTrue(packageScript.contains("5oqV5rOo5oiQ5Yqf5py65Zmo5Lq6"))
        assertTrue(packageScript.contains("AdsPower"))
        assertTrue(
            packageScript.contains("'config', 'data', 'logs', 'backups', 'updates', 'accounts', 'telegram settings'"),
            "update package manifest must document old-user local data preservation"
        )

        assertTrue(
            launchScript.contains("\$localConfig = Join-Path \$rootDir 'config\\local.env.ps1'"),
            "launcher must keep old-version local environment overrides portable"
        )
        assertTrue(
            launchScript.contains("\$databaseVolumeName = 'odds-monitor-mysql-data'"),
            "launcher must reuse the old MySQL volume instead of creating a new empty database"
        )
        assertTrue(
            launchScript.contains("Test-FrontendRuntimeCurrent"),
            "launcher must restart a stale frontend server from another extracted package directory"
        )
        assertTrue(
            launchScript.contains("Frontend service belongs to a different install or stale dist; restarting frontend"),
            "launcher must explain why it restarts a stale frontend server"
        )
        assertTrue(
            startScript.contains("\$env:ODDS_MONITOR_PACKAGE_DEFAULT_ADMIN_ENABLED"),
            "packaged local login must remain available after upgrading old installs"
        )
    }

    @Test
    fun `full package includes runtime and visible current features`() {
        val packageScript = readRootFile("build-odds-monitor-full-package.ps1")

        assertTrue(
            packageScript.contains("odds-monitor-full-v${'$'}version"),
            "full package name must be separate from update packages"
        )
        assertTrue(
            packageScript.contains("Copy-Item -LiteralPath ${'$'}javaHome"),
            "full package must include the bundled Java runtime for fresh installs"
        )
        assertTrue(
            packageScript.contains("ODDS_MONITOR_PACKAGE_DEFAULT_ADMIN_ENABLED"),
            "full package must keep the packaged default login available"
        )
        assertTrue(
            packageScript.contains("assertPackagedAssetContains"),
            "full package must verify packaged frontend assets include current visible features"
        )
        assertTrue(packageScript.contains("5rWL6K+V5qih5byP"))
        assertTrue(packageScript.contains("5oqV5rOo5oiQ5Yqf5py65Zmo5Lq6"))
        assertTrue(packageScript.contains("AdsPower"))
    }

    private fun readRootFile(relativePath: String): String {
        return Files.readString(Path.of("..", relativePath))
    }
}
